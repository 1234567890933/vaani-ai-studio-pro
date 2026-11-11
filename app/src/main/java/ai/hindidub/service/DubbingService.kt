package ai.hindidub.service

import ai.hindidub.MainActivity
import ai.hindidub.R
import ai.hindidub.core.AppPrefs
import ai.hindidub.core.JobQueue
import ai.hindidub.core.LanguageCatalog
import ai.hindidub.engine.EngineManager
import ai.hindidub.engine.core.DubEngine
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Queue worker.
 *
 * One service, one DubEngine instance, one job at a time — and the engine (with its loaded
 * Whisper context and ONNX sessions) is created once and reused for the whole queue. That is
 * the difference between "five episodes" costing five model loads or one.
 */
class DubbingService : Service() {

    data class State(
        val running: Boolean = false,
        val stage: String = "",
        val fraction: Float = 0f,
        val message: String = "",
        val etaMs: Long = -1,
        val outputPath: String? = null,
        val error: String? = null,
        val segments: Int = 0,
        val elapsedMs: Long = 0,
        val queued: Int = 0,
        val statsLine: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var engine: DubEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Preparing…", 0f))
        acquireWakeLock()
        if (job?.isActive == true) return START_NOT_STICKY

        job = scope.launch {
            val queue = JobQueue(applicationContext)
            val prefs = AppPrefs(applicationContext)
            val manager = EngineManager(applicationContext).apply {
                asrGroupId = prefs.asrGroupId
                mtGroupId = prefs.mtGroupId
                threads = if (prefs.threads > 0) prefs.threads else threads
                pool.quotaBytes = prefs.quotaGb * 1024L * 1024L * 1024L
            }
            val dub = DubEngine(applicationContext, manager)
            engine = dub

            try {
                while (true) {
                    val next = queue.next() ?: break
                    queue.update(next.copy(status = "RUNNING"))
                    val started = System.currentTimeMillis()
                    _state.value = State(running = true, stage = "START", message = next.name)

                    try {
                        val cfg = DubEngine.Config(
                            input = Uri.parse(next.uri),
                            src = LanguageCatalog.byCode(prefs.sourceLang)
                                ?: LanguageCatalog.byCode("en")!!,
                            tgt = LanguageCatalog.targetByCode(prefs.targetLang)
                                ?: LanguageCatalog.targetByCode("hi")!!,
                            voiceProfileId = prefs.voiceProfileId,
                            bedGainDb = prefs.bedGainDb,
                            suppressCenter = prefs.suppressCenter,
                            translationMode = prefs.translationMode,
                            routingMode = prefs.routingMode,
                            providerOrder = prefs.providerOrder,
                            quickSeconds = prefs.quickSeconds,
                            mixSampleRate = prefs.mixSampleRate,
                            aacKbps = prefs.aacKbps,
                            cloudEnabled = prefs.cloudEnabled,
                            networkAvailable = ai.hindidub.core.NetworkState.isOnline(applicationContext),
                            configuredProviders = prefs.providerOrder,
                            cloudTtsProvider = prefs.cloudTtsProvider
                        )

                        val result = dub.run(cfg) { p ->
                            val elapsed = System.currentTimeMillis() - started
                            val eta = if (p.fraction > 0.02f && p.fraction < 1f) {
                                (elapsed * (1f - p.fraction) / p.fraction).toLong()
                            } else -1L
                            _state.value = _state.value.copy(
                                running = true, stage = p.stage.name,
                                fraction = p.fraction, message = p.message, etaMs = eta
                            )
                            notifyProgress(p.message, p.fraction)
                        }

                        // Peak sidecar so the player can draw the real waveform.
                        runCatching {
                            File(result.output.parentFile, result.output.name + ".peaks")
                                .writeText(result.peaks.joinToString(","))
                        }
                        queue.update(
                            next.copy(status = "DONE", outputPath = result.output.absolutePath)
                        )
                        _state.value = _state.value.copy(
                            running = false, stage = "DONE", fraction = 1f,
                            message = "Saved ${result.output.name}",
                            outputPath = result.output.absolutePath,
                            segments = result.segments, elapsedMs = result.elapsedMs,
                            etaMs = 0,
                            statsLine = "ASR RTF ${"%.2f".format(result.stats.asrRtf)}× · " +
                                "speech ${(result.stats.speechRatio * 100).toInt()}% · " +
                                "${result.stats.windows} windows · ${result.stats.cache} · " +
                                "mix gain ${"%.2f".format(result.stats.mixGain)} · " +
                                "tones ${result.stats.moods} · " +
                                "peak ${result.stats.peakWindowMb} MB"
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "job failed", t)
                        queue.update(next.copy(status = "FAILED", error = t.message))
                        _state.value = _state.value.copy(
                            running = false, stage = "ERROR",
                            error = t.message ?: t::class.java.simpleName
                        )
                    }
                    _state.value = _state.value.copy(
                        queued = queue.all().count { it.status == "PENDING" }
                    )
                }
            } finally {
                dub.release()
                engine = null
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        engine?.release()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(text: String, fraction: Float): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
            .setContentIntent(open)
            .build()
    }

    private fun notifyProgress(text: String, fraction: Float) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, fraction))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HindiDubber:job").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "DubbingService"
        const val ACTION_START = "ai.hindidub.action.START"
        const val CHANNEL_ID = "dubbing_jobs"
        const val NOTIF_ID = 1001

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        fun reset() {
            _state.value = State()
        }
    }
}
