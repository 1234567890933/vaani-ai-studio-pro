package ai.hindidub.setup

import ai.hindidub.core.AppPrefs
import ai.hindidub.engine.EngineManager
import ai.hindidub.model.ModelCatalog
import ai.hindidub.model.ModelGroup
import ai.hindidub.util.DeviceProfiler
import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * First-run setup.
 *
 * Order matters and is deliberate:
 *  1. the **bundled** tiny model is copied out of the APK, so the app can dub a clip
 *     immediately, before any download;
 *  2. then the tier the device's RAM can actually run is downloaded;
 *  3. then the TTS voice is checked, because a missing voice is the most common silent
 *     failure (the dub comes out empty).
 *
 * Every step reports a fraction so the UI can animate real progress instead of a spinner.
 */
class SetupManager(
    private val context: Context,
    private val engine: EngineManager,
    private val prefs: AppPrefs
) {

    data class Step(val name: String, val fraction: Float, val detail: String)

    suspend fun installBundled(onStep: (Step) -> Unit): Boolean {
        val group = ModelCatalog.asrTiny
        onStep(Step("Starter model", 0.05f, "copying from the app"))
        val ok = engine.repo.installBundled(group)
        if (ok) {
            prefs.asrGroupId = group.id
            engine.asrGroupId = group.id
            prefs.flush()
        }
        return ok
    }

    suspend fun autoSetup(onStep: (Step) -> Unit): Boolean {
        val profile = DeviceProfiler.profile(context)
        val asr = ModelCatalog.asrFor(profile.ramMb)
        val mt = ModelCatalog.mtFor(profile.ramMb)

        prefs.asrGroupId = asr.id
        prefs.mtGroupId = mt.id
        prefs.targetLang = "hi"
        prefs.threads = profile.recommendedThreads
        engine.asrGroupId = asr.id
        engine.mtGroupId = mt.id

        val groups = listOf(asr, mt)
        val totalBytes = groups.sumOf { it.totalBytes }.coerceAtLeast(1L)
        var doneBytes = 0L

        for (g in groups) {
            if (engine.pool.isInstalled(g)) {
                doneBytes += g.totalBytes
                onStep(Step(g.label, doneBytes.toFloat() / totalBytes, "already installed"))
                continue
            }
            onStep(Step(g.label, doneBytes.toFloat() / totalBytes, "downloading ${g.totalMb} MB"))
            try {
                engine.repo.downloadGroup(g) { done, total, file ->
                    val overall = (doneBytes + done.coerceAtMost(g.totalBytes)).toFloat() / totalBytes
                    onStep(
                        Step(
                            g.label, overall,
                            "$file  ${done / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "download failed for ${g.id}", t)
                onStep(Step(g.label, doneBytes.toFloat() / totalBytes, "failed: ${t.message}"))
                return false
            }
            doneBytes += g.totalBytes
        }

        val voiceOk = hasVoice(prefs.targetLang)
        onStep(
            Step(
                "Voice", 1f,
                if (voiceOk) "offline voice ready"
                else "install the offline voice in Settings → Text-to-speech"
            )
        )
        prefs.setupDone = true
        prefs.flush()
        return true
    }

    suspend fun hasVoice(langCode: String): Boolean {
        val lang = ai.hindidub.core.LanguageCatalog.targetByCode(langCode) ?: return false
        val tts = suspendCancellableCoroutine<TextToSpeech?> { cont ->
            var ref: TextToSpeech? = null
            ref = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) cont.resume(ref) else cont.resume(null)
            }
        } ?: return false
        return try {
            tts.isLanguageAvailable(Locale.forLanguageTag(lang.tts)) >= TextToSpeech.LANG_AVAILABLE
        } finally {
            tts.shutdown()
        }
    }

    fun statusLine(): String {
        val asr = ModelCatalog.byId(prefs.asrGroupId)
        val mt = ModelCatalog.byId(prefs.mtGroupId)
        val asrOk = asr?.let { engine.pool.isInstalled(it) } == true
        val mtOk = mt?.let { engine.pool.isInstalled(it) } == true
        return when {
            asrOk && mtOk -> "ready"
            asrOk -> "ASR only — add a translation model"
            else -> "models missing"
        }
    }

    fun nextMissingGroup(): ModelGroup? {
        val asr = ModelCatalog.byId(prefs.asrGroupId)
        val mt = ModelCatalog.byId(prefs.mtGroupId)
        return listOfNotNull(asr, mt).firstOrNull { !engine.pool.isInstalled(it) }
    }

    private companion object {
        const val TAG = "Setup"
    }
}
