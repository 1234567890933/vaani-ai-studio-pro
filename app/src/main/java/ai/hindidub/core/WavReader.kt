package ai.hindidub.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE parser for PCM16 and float32 payloads.
 *
 * Needed because the cloud TTS providers return audio, and Android's own TTS path writes a WAV
 * file. Parsing it in ~40 lines of pure Kotlin (instead of pulling in an audio library or going
 * through MediaCodec) keeps the pipeline testable on a plain JVM — the unit tests feed it
 * synthetic WAVs and check the samples come back exactly.
 */
object WavReader {

    data class Audio(val samples: FloatArray, val sampleRate: Int, val channels: Int)

    fun parse(bytes: ByteArray): Audio? {
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null

        var channels = 1
        var sampleRate = 16000
        var bits = 16
        var isFloat = false
        var dataOffset = -1
        var dataSize = 0

        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4)
            val size = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val body = i + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 <= bytes.size) {
                        val bb = ByteBuffer.wrap(bytes, body, 16).order(ByteOrder.LITTLE_ENDIAN)
                        val format = bb.short.toInt()
                        channels = bb.short.toInt().coerceAtLeast(1)
                        sampleRate = bb.int
                        bb.int
                        bb.short
                        bits = bb.short.toInt()
                        isFloat = format == 3
                    }
                }
                "data" -> {
                    dataOffset = body
                    dataSize = size.coerceAtMost(bytes.size - body)
                    break
                }
            }
            i = body + size + (size and 1)
        }
        if (dataOffset < 0 || dataSize <= 0) return null

        val out: FloatArray
        if (isFloat && bits == 32) {
            val fb = ByteBuffer.wrap(bytes, dataOffset, dataSize)
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            out = FloatArray(fb.remaining())
            fb.get(out)
        } else if (bits == 16) {
            val sb = ByteBuffer.wrap(bytes, dataOffset, dataSize)
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            out = FloatArray(sb.remaining())
            for (k in out.indices) out[k] = sb.get() / 32768f
        } else {
            return null
        }
        return Audio(out, sampleRate, channels)
    }

    /** Downmixes to mono, which is what the mixer and the timeline use. */
    fun toMono(audio: Audio): FloatArray {
        if (audio.channels <= 1) return audio.samples
        val frames = audio.samples.size / audio.channels
        val out = FloatArray(frames)
        var idx = 0
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until audio.channels) acc += audio.samples[idx++]
            out[f] = acc / audio.channels
        }
        return out
    }
}
