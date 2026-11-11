package ai.hindidub.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioUtils {

    /** Naive linear resampler. Good enough for ASR input and TTS output. */
    fun resampleLinear(input: FloatArray, srcSr: Int, dstSr: Int): FloatArray {
        if (input.isEmpty() || srcSr <= 0 || dstSr <= 0 || srcSr == dstSr) return input
        val ratio = dstSr.toDouble() / srcSr
        val outLen = (input.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i / ratio
            val i0 = pos.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }

    fun toPcm16Bytes(pcm: FloatArray): ByteArray {
        val bytes = ByteArray(pcm.size * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            bb.putShort(v.toShort())
        }
        return bytes
    }

    /**
     * Reads a 16-bit PCM WAV file (what Android TTS writes) into floats.
     * Chunk-walks the RIFF header instead of assuming a 44-byte header.
     */
    fun readWavPcm16(file: File): FloatArray? {
        if (!file.exists() || file.length() < 44) return null
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            if (String(header, 0, 4) != "RIFF") return null

            var channels = 1
            var bits = 16
            var dataOffset = -1L
            var dataSize = 0L

            val chunkHeader = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt().coerceAtMost(64))
                        raf.readFully(fmt)
                        channels = ByteBuffer.wrap(fmt, 2, 2)
                            .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                        bits = ByteBuffer.wrap(fmt, 14, 2)
                            .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                        if (size % 2 == 1L) raf.skipBytes(1)
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = size.coerceAtMost(raf.length() - raf.filePointer)
                        break
                    }
                    else -> {
                        raf.seek(raf.filePointer + size + if (size % 2 == 1L) 1 else 0)
                    }
                }
            }
            if (dataOffset < 0 || dataSize <= 0) return null
            if (bits != 16) return null   // Android TTS emits 16-bit PCM

            raf.seek(dataOffset)
            val raw = ByteArray(dataSize.toInt())
            raf.readFully(raw)
            val sb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frames = sb.remaining() / channels
            val out = FloatArray(frames)
            for (i in 0 until frames) {
                var acc = 0f
                for (c in 0 until channels) acc += sb.get() / 32768f
                out[i] = acc / channels
            }
            return out
        }
    }

    /** Writes mono 16-bit PCM as a WAV file (used by the benchmark + debug dumps). */
    fun writeWav(file: File, pcm: FloatArray, sampleRate: Int) {
        val data = toPcm16Bytes(pcm)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + data.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)                                   // PCM
        header.putShort(1)                                   // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)                        // byte rate
        header.putShort(2)                                   // block align
        header.putShort(16)                                  // bits per sample
        header.put("data".toByteArray())
        header.putInt(data.size)
        file.outputStream().use { os ->
            os.write(header.array())
            os.write(data)
        }
    }
}
