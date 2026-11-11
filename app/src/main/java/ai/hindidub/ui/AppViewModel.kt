package ai.hindidub.ui

import ai.hindidub.cloud.CloudTranslator
import ai.hindidub.cloud.Providers
import ai.hindidub.core.AppPrefs
import ai.hindidub.core.CrashLogger
import ai.hindidub.core.EngineRouter
import ai.hindidub.core.NetworkState
import ai.hindidub.core.DubLanguage
import ai.hindidub.core.IntentRelay
import ai.hindidub.core.JobQueue
import ai.hindidub.core.LanguageCatalog
import ai.hindidub.core.ResourceGovernor
import ai.hindidub.core.SecretStore
import ai.hindidub.core.StorageManager
import ai.hindidub.engine.EngineManager
import ai.hindidub.engine.VoiceProfiles
import ai.hindidub.model.ModelCatalog
import ai.hindidub.model.ModelGroup
import ai.hindidub.service.DubbingService
import ai.hindidub.setup.SetupManager
import ai.hindidub.util.DeviceProfiler
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single source of truth for the UI.
 *
 * Everything slow — model bookkeeping, storage scans, peak loading — runs on Dispatchers.IO and
 * lands in [state]; the Compose screens only read it. No disk access happens on a progress
 * callback, which is what kept the old build janky.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val engine = EngineManager(app)
    private val setup = SetupManager(app, engine, prefs)
    private val queue = JobQueue(app)
    private val governor = ResourceGovernor(app)
    private val cloud = CloudTranslator(app)
    private val profile = DeviceProfiler.profile(app)

    private val movies = File(
        app.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: app.filesDir,
        "dubbed"
    )
    private val storage = StorageManager(movies)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        queue.recoverInterrupted()
        applyPrefs()
        _state.value = _state.value.copy(
            settings = readSettings(),
            statusLine = setup.statusLine(),
            poolSummary = engine.pool.summary(),
            deviceLine = "${profile.soc} · ${profile.cores} cores · ${profile.ramMb} MB RAM",
            providerStatus = cloud.statusLines(prefs.providerOrder),
            networkOnline = NetworkState.isOnline(app),
            keyPresent = keyPresence(),
            planSummary = planSummary()
        )
        refreshStorage()
        viewModelScope.launch { checkVoice() }
        viewModelScope.launch { DubbingService.state.collect { onService(it) } }
        viewModelScope.launch {
            IntentRelay.incoming.collect { incoming ->
                if (incoming != null) {
                    attach(incoming.uri, incoming.name)
                    IntentRelay.consume()
                }
            }
        }
    }

    // ---- navigation -------------------------------------------------------

    fun go(screen: Screen) {
        refreshPlan()
        _state.value = _state.value.copy(screen = screen)
    }
    fun back() = go(Screen.Home)
    fun openSettings() = go(Screen.Settings)

    fun openPlayer(path: String) {
        _state.value = _state.value.copy(outputPath = path, peaks = null)
        viewModelScope.launch {
            val peaks = loadPeaks(File(path))
            _state.value = _state.value.copy(peaks = peaks)
        }
        go(Screen.Player)
    }

    // ---- input / job ------------------------------------------------------

    fun attach(uri: Uri?, name: String) {
        _state.value = _state.value.copy(inputUri = uri, inputName = name, notice = null)
    }

    fun startDub() {
        val st = _state.value
        val uri = st.inputUri ?: run {
            notice("Attach a video first.")
            return
        }
        setup.nextMissingGroup()?.let {
            notice("Model missing: ${it.label}. Open Settings → Models.")
            return
        }
        governor.shouldWarnBeforeStart()?.let { notice(it) }

        queue.enqueue(uri.toString(), st.inputName)
        _state.value = _state.value.copy(
            inputUri = null,
            inputName = "",
            running = true,
            stage = "QUEUED",
            stageMessage = "Starting",
            fraction = 0f,
            screen = Screen.Processing,
            queueCount = queue.all().count { it.status == "PENDING" }
        )
        startService()
    }

    fun cancelJob() {
        getApplication<Application>().stopService(
            Intent(getApplication(), DubbingService::class.java)
        )
        _state.value = _state.value.copy(running = false, screen = Screen.Home)
        notice("Job cancelled.")
    }

    private fun startService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, DubbingService::class.java).setAction(DubbingService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }

    private fun onService(s: DubbingService.State) {
        val current = _state.value
        val screen = when {
            s.outputPath != null -> Screen.Player
            s.running || current.running -> Screen.Processing
            else -> current.screen
        }
        _state.value = current.copy(
            running = s.running,
            stage = s.stage.ifBlank { current.stage },
            stageMessage = s.message.ifBlank { current.stageMessage },
            fraction = if (s.running) s.fraction else current.fraction,
            etaMs = s.etaMs,
            lastStats = s.statsLine.ifBlank { current.lastStats },
            outputPath = s.outputPath ?: current.outputPath,
            queueCount = s.queued,
            screen = screen,
            statusLine = if (!s.running) setup.statusLine() else current.statusLine
        )
        if (s.outputPath != null) {
            s.outputPath.let { path ->
                viewModelScope.launch {
                    val peaks = loadPeaks(File(path))
                    _state.value = _state.value.copy(peaks = peaks)
                }
            }
            refreshStorage()
        }
        if (s.error != null) notice(s.error)
    }

    // ---- models -----------------------------------------------------------

    /** One tap: install the bundled starter model, then fetch the recommended tier. */
    fun downloadEverything() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyLabel = "Preparing", busyFraction = 0f)
            val ok = setup.autoSetup { step ->
                _state.value = _state.value.copy(
                    busyLabel = "${step.name}: ${step.detail}",
                    busyFraction = step.fraction
                )
            }
            val voiceOk = setup.hasVoice(prefs.targetLang)
            prefs.flush()
            _state.value = _state.value.copy(
                busy = false,
                modelsReady = ok,
                voiceReady = voiceOk,
                statusLine = setup.statusLine(),
                poolSummary = engine.pool.summary(),
                notice = when {
                    ok && voiceOk -> "Models ready — you can dub now."
                    ok -> "Models ready. Install the offline voice in system settings."
                    else -> "Download stopped early — check storage and connection."
                }
            )
            refreshStorage()
        }
    }

    fun installBundledModel() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyLabel = "Installing starter model")
            setup.installBundled { }
            prefs.flush()
            _state.value = _state.value.copy(
                busy = false,
                settings = readSettings(),
                statusLine = setup.statusLine(),
                modelsReady = setup.statusLine() == "ready"
            )
        }
    }

    fun download(group: ModelGroup) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyLabel = group.label, busyFraction = 0f)
            try {
                engine.repo.downloadGroup(group) { done, total, file ->
                    _state.value = _state.value.copy(
                        busyLabel = file,
                        busyFraction = if (total > 0) done.toFloat() / total else 0f
                    )
                }
            } catch (t: Throwable) {
                notice("Download failed: ${t.message}")
            } finally {
                _state.value = _state.value.copy(
                    busy = false, statusLine = setup.statusLine(), poolSummary = engine.pool.summary()
                )
            }
        }
    }

    fun useGroup(group: ModelGroup) {
        if (group.kind == ModelGroup.Kind.ASR) {
            prefs.asrGroupId = group.id
            engine.asrGroupId = group.id
        } else if (group.kind == ModelGroup.Kind.MT) {
            prefs.mtGroupId = group.id
            engine.mtGroupId = group.id
        }
        prefs.flush()
        _state.value = _state.value.copy(settings = readSettings(), statusLine = setup.statusLine())
    }

    fun isInstalled(group: ModelGroup): Boolean = engine.pool.isInstalled(group)

    // ---- storage ----------------------------------------------------------

    fun refreshStorage() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { storage.outputs() }
            _state.value = _state.value.copy(outputs = list, storageTotal = list.sumOf { it.bytes })
        }
    }

    fun deleteOutput(output: StorageManager.Output) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { storage.delete(output) }
            File(movies, output.name + ".peaks").delete()
            refreshStorage()
            notice("Deleted ${output.name}")
        }
    }

    // ---- settings ---------------------------------------------------------

    fun setSourceLang(code: String) = persist { prefs.sourceLang = code }
    fun setTargetLang(code: String) = persist {
        prefs.targetLang = code
        viewModelScope.launch { checkVoice() }
    }
    fun setVoiceProfile(id: String) = persist { prefs.voiceProfileId = id }
    fun setBedGain(db: Float) = persist { prefs.bedGainDb = db }
    fun setSuppressCenter(v: Boolean) = persist { prefs.suppressCenter = v }
    fun setThreads(n: Int) = persist { prefs.threads = n; engine.threads = n }
    fun setQuotaGb(n: Int) = persist {
        prefs.quotaGb = n
        engine.pool.quotaBytes = n * 1024L * 1024L * 1024L
    }
    fun setSampleRate(hz: Int) = persist { prefs.mixSampleRate = hz }
    fun setBitrate(kbps: Int) = persist { prefs.aacKbps = kbps }
    fun setTheme(mode: String) = persist { prefs.themeMode = mode }
    fun setTranslationMode(mode: String) = persist { prefs.translationMode = mode }
    fun setRoutingMode(mode: String) = persist { prefs.routingMode = mode }
    fun setQuickMode(on: Boolean) = persist { prefs.quickSeconds = if (on) 60 else 0 }

    fun setCloudEnabled(on: Boolean) = persist {
        prefs.cloudEnabled = on
        _state.value = _state.value.copy(
            networkOnline = NetworkState.isOnline(getApplication()),
            planSummary = planSummary()
        )
    }

    fun setCloudTtsProvider(id: String) = persist {
        prefs.cloudTtsProvider = id
        _state.value = _state.value.copy(planSummary = planSummary())
    }

    fun setProviderEnabled(id: String, enabled: Boolean) = persist {
        val current = prefs.providerOrder.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        prefs.providerOrder = (if (enabled) (current + id).distinct() else current - id).joinToString(",")
        _state.value = _state.value.copy(providerStatus = cloud.statusLines(prefs.providerOrder))
    }

    fun saveKey(providerId: String, key: String) {
        SecretStore.put(getApplication(), CloudTranslator.keyName(providerId), key.trim())
        _state.value = _state.value.copy(
            providerStatus = cloud.statusLines(prefs.providerOrder),
            keyPresent = keyPresence(),
            planSummary = planSummary(),
            notice = if (key.isBlank()) "Key removed" else "Key saved in the Android Keystore"
        )
    }

    private fun keyPresence(): Map<String, Boolean> =
        Providers.all.associate { it.id to SecretStore.has(getApplication(), CloudTranslator.keyName(it.id)) }

    /** What the next job will actually use, from the same router the engine runs. */
    fun planSummary(): String = EngineRouter.plan(
        EngineRouter.Request(
            cloudEnabled = prefs.cloudEnabled,
            networkAvailable = NetworkState.isOnline(getApplication()),
            configuredProviders = prefs.providerOrder,
            cloudTtsProvider = prefs.cloudTtsProvider.ifBlank { null }
        )
    ).summary()

    fun refreshPlan() {
        _state.value = _state.value.copy(
            networkOnline = NetworkState.isOnline(getApplication()),
            planSummary = planSummary(),
            keyPresent = keyPresence()
        )
    }

    fun keyFor(providerId: String): String =
        SecretStore.get(getApplication(), CloudTranslator.keyName(providerId)).orEmpty()

    fun crashLog(): String? = CrashLogger.lastCrash(getApplication())
    fun clearCrashLog() {
        CrashLogger.clear(getApplication())
        notice("Crash log cleared")
    }

    fun clearNotice() = _state.value.let { _state.value = it.copy(notice = null) }

    private fun notice(text: String) = _state.value.let { _state.value = it.copy(notice = text) }

    private fun persist(block: () -> Unit) {
        block()
        _state.value = _state.value.copy(settings = readSettings())
    }

    private fun readSettings() = UiSettings(
        sourceLang = prefs.sourceLang,
        targetLang = prefs.targetLang,
        voiceProfileId = prefs.voiceProfileId,
        bedGainDb = prefs.bedGainDb,
        suppressCenter = prefs.suppressCenter,
        asrGroupId = prefs.asrGroupId,
        mtGroupId = prefs.mtGroupId,
        translationMode = prefs.translationMode,
        routingMode = prefs.routingMode,
        providerOrder = prefs.providerOrder,
        threads = if (prefs.threads > 0) prefs.threads else profile.recommendedThreads,
        quotaGb = prefs.quotaGb,
        mixSampleRate = prefs.mixSampleRate,
        aacKbps = prefs.aacKbps,
        themeMode = prefs.themeMode,
        quickSeconds = prefs.quickSeconds,
        cloudEnabled = prefs.cloudEnabled,
        cloudTtsProvider = prefs.cloudTtsProvider
    )

    private fun applyPrefs() {
        engine.asrGroupId = prefs.asrGroupId
        engine.mtGroupId = prefs.mtGroupId
        engine.threads = if (prefs.threads > 0) prefs.threads else profile.recommendedThreads
        engine.pool.quotaBytes = prefs.quotaGb * 1024L * 1024L * 1024L
    }

    private suspend fun checkVoice() {
        _state.value = _state.value.copy(voiceReady = setup.hasVoice(prefs.targetLang))
    }

    /** Peak sidecar written by the engine next to each output; falls back to null. */
    private suspend fun loadPeaks(file: File): FloatArray? = withContext(Dispatchers.IO) {
        runCatching {
            val sidecar = File(file.parentFile, file.name + ".peaks")
            if (!sidecar.exists()) return@runCatching null
            sidecar.readText().split(',').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
        }.getOrNull()
    }

    override fun onCleared() {
        prefs.flush()
        super.onCleared()
    }

    // ---- helpers used by the screens -------------------------------------

    fun languages(): List<DubLanguage> = LanguageCatalog.sources
    fun targetLanguages(): List<DubLanguage> = LanguageCatalog.targets
    fun popular(): List<DubLanguage> = LanguageCatalog.popular()
    fun voiceProfiles() = VoiceProfiles.all
    fun providers() = Providers.all
    fun groups() = ModelCatalog.all
    fun asrGroups() = ModelCatalog.asrGroups
    fun mtGroups() = ModelCatalog.mtGroups
    fun targetName(): String =
        LanguageCatalog.targetByCode(prefs.targetLang)?.nativeName ?: "Hindi"

    companion object {
        private const val TAG = "AppVM"

        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST", "DEPRECATION")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(app) as T
            }
    }
}
