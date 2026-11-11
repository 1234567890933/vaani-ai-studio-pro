package ai.hindidub.engine

import ai.hindidub.model.ModelCatalog
import ai.hindidub.model.ModelGroup
import ai.hindidub.model.ModelPool
import ai.hindidub.model.ModelRepository
import ai.hindidub.util.DeviceProfiler
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Single place that knows where models live and how the device should be driven.
 *
 * Defaults are chosen for a Dimensity 6000-class phone: 4 threads (2 big + 6 LITTLE means
 * more threads only add contention), GPU off (the Vulkan path was slower than CPU on
 * comparable Mali parts — measure before trusting it), NNAPI off.
 */
class EngineManager(private val context: Context) {

    val pool = ModelPool(context, File(context.filesDir, "models"))
    val repo = ModelRepository(context, pool)

    var threads: Int = 4
    var useGpu: Boolean = false
    var useNnapi: Boolean = false

    var asrGroupId: String = "asr-small"
    var mtGroupId: String = "mt-nllb"

    fun asrGroup(): ModelGroup? = ModelCatalog.byId(asrGroupId)
    fun mtGroup(): ModelGroup? = ModelCatalog.byId(mtGroupId)

    fun whisperModelFile(): File? {
        val g = asrGroup() ?: return null
        return g.files.firstOrNull()?.let { pool.fileFor(it) }?.takeIf { it.exists() }
    }

    fun hasAsr(): Boolean = asrGroup()?.let { pool.isInstalled(it) } == true
    fun hasMt(): Boolean = mtGroup()?.let { pool.isInstalled(it) } == true

    fun statusLine(): String {
        val asr = asrGroup()?.let { if (pool.isInstalled(it)) it.label else "missing" } ?: "missing"
        val mt = mtGroup()?.let { if (pool.isInstalled(it)) it.label else "missing" } ?: "missing"
        return "ASR: $asr\nMT: $mt\n${pool.summary()}"
    }

    /** Applies device-appropriate defaults the first time the app runs. */
    fun applyDeviceDefaults(profile: DeviceProfiler.Info) {
        threads = profile.recommendedThreads
        if (!profile.recommendSmallWhisper) asrGroupId = "asr-base"
        Log.i(TAG, "defaults: threads=$threads asr=$asrGroupId")
    }

    fun buildAsr(): AsrProcessor {
        val model = whisperModelFile() ?: error("Whisper model not downloaded")
        return AsrProcessor(model, useGpu).also { it.load() }
    }

    fun buildTranslator(): TranslationEngine {
        val g = mtGroup() ?: error("No translation model selected")
        check(pool.isInstalled(g)) { "Translation model not downloaded" }
        val dir = pool.fileFor(g.files.first()).parentFile ?: error("bad model path")
        return TranslationEngine(dir, threads, useNnapi).also { it.load() }
    }


    private companion object {
        const val TAG = "EngineManager"
    }
}
