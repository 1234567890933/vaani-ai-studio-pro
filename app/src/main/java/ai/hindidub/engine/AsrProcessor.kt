package ai.hindidub.engine

import android.util.Log
import java.io.Closeable
import java.io.File

/**
 * Local speech recognition with whisper.cpp.
 *
 * Whisper internally windows at 30 s, so a long file is handled in one native call
 * without extra RAM: the PCM buffer is the only large allocation on the Kotlin side
 * (~230 MB for a 2-hour film at 16 kHz float — for very long inputs use [chunked]).
 */
class AsrProcessor(
    private val modelFile: File,
    private val useGpu: Boolean = false
) : Closeable {

    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    private var handle = 0L

    fun load() {
        require(modelFile.exists()) { "Whisper model not found: ${modelFile.absolutePath}" }
        handle = WhisperBridge.nativeInit(modelFile.absolutePath, useGpu)
        check(handle != 0L) { "whisper_init failed — corrupt or unsupported ggml file" }
        Log.i(TAG, "whisper loaded: ${modelFile.name} (gpu=$useGpu)")
    }

    fun transcribe(pcm16k: FloatArray, language: String, threads: Int): List<Segment> {
        check(handle != 0L) { "call load() first" }
        val raw = WhisperBridge.nativeTranscribe(handle, pcm16k, language, threads, false)
        return parse(raw)
    }

    /** Splits very long audio into 10-minute blocks to cap peak RAM. */
    fun transcribeChunked(
        pcm16k: FloatArray,
        language: String,
        threads: Int,
        chunkMs: Int = 10 * 60 * 1000,
        onChunk: (Float) -> Unit = {}
    ): List<Segment> {
        val chunkSamples = (chunkMs / 1000) * AudioExtractor.TARGET_SR
        if (pcm16k.size <= chunkSamples) {
            onChunk(1f)
            return transcribe(pcm16k, language, threads)
        }
        val all = ArrayList<Segment>()
        var offset = 0
        while (offset < pcm16k.size) {
            val end = (offset + chunkSamples).coerceAtMost(pcm16k.size)
            val slice = pcm16k.copyOfRange(offset, end)
            val offsetMs = offset * 1000L / AudioExtractor.TARGET_SR
            all += transcribe(slice, language, threads).map {
                it.copy(startMs = it.startMs + offsetMs, endMs = it.endMs + offsetMs)
            }
            offset = end
            onChunk(offset.toFloat() / pcm16k.size)
        }
        return all
    }

    private fun parse(raw: String): List<Segment> =
        raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val start = parts[0].toLongOrNull() ?: return@mapNotNull null
                val end = parts[1].toLongOrNull() ?: return@mapNotNull null
                val text = parts[2].trim()
                if (text.isEmpty()) null else Segment(start, end, text)
            }
            .toList()

    override fun close() {
        if (handle != 0L) {
            WhisperBridge.nativeFree(handle)
            handle = 0L
        }
    }

    private companion object {
        const val TAG = "ASR"
    }
}
