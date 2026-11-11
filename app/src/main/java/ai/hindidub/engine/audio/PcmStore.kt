package ai.hindidub.engine.audio

import ai.hindidub.core.Diag
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Disk-backed PCM16 audio.
 *
 * Why this exists: the dubbed voice timeline and the original sound bed used to live in
 * FloatArrays. For a 24-minute episode that is 138 MB (voice) + 276 MB (stereo bed) held
 * simultaneously — on a 4 GB phone with a 1 GB translation model that is an OOM waiting to
 * happen. On disk the same audio is 69 MB + 138 MB, and peak RAM becomes one small block.
 *
 * The file is raw PCM16 little-endian, no header; [sampleRate] and [channels] are carried
 * here. Sparse writes are supported (RandomAccessFile.seek + write), which is exactly what a
 * timeline needs: write each segment at its timestamp, leave silence everywhere else.
 */
class PcmStore(
    val file: File,
    val sampleRate: Int,
    val channels: Int
) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private val bytesPerFrame = channels * 2

    init {
        file.parentFile?.mkdirs()
    }

    /** Grows the file to hold [frames] frames, zero-filled (silence). */
    fun ensureFrames(frames: Long) {
        val needed = frames * bytesPerFrame
        if (raf.length() < needed) raf.setLength(needed)
    }

    /** Writes [frames] interleaved frames starting at frame [frameIndex]. */
    fun writeFrames(frameIndex: Long, pcm: FloatArray, frames: Int, sourceOffset: Int = 0) {
        if (frames <= 0) return
        val bytes = ByteArray(frames * bytesPerFrame)
        var o = 0
        var i = sourceOffset
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val v = (pcm[i++].coerceIn(-1f, 1f) * 32767f).toInt()
                bytes[o++] = (v and 0xFF).toByte()
                bytes[o++] = ((v shr 8) and 0xFF).toByte()
            }
        }
        raf.seek(frameIndex * bytesPerFrame)
        raf.write(bytes)
    }

    /** Convenience for the mono voice timeline. */
    fun writeMono(frameIndex: Long, pcm: FloatArray, frames: Int) {
        require(channels == 1) { "writeMono on a ${channels}-channel store" }
        writeFrames(frameIndex, pcm, frames)
    }

    /** Reads up to [frames] frames; returns interleaved floats (shorter at end of file). */
    fun readFrames(frameIndex: Long, frames: Int): FloatArray {
        val available = ((frameCount() - frameIndex).coerceAtLeast(0)).toInt()
        val n = minOf(frames, available)
        if (n <= 0) return FloatArray(0)
        val bytes = ByteArray(n * bytesPerFrame)
        raf.seek(frameIndex * bytesPerFrame)
        raf.readFully(bytes)
        val out = FloatArray(n * channels)
        var o = 0
        for (i in 0 until n * channels) {
            val lo = bytes[o].toInt() and 0xFF
            val hi = bytes[o + 1].toInt()
            val v = (hi shl 8) or lo
            out[i] = v.toShort() / 32768f
            o += 2
        }
        return out
    }

    fun frameCount(): Long = raf.length() / bytesPerFrame

    fun closeAndDelete() {
        runCatching { raf.close() }
        if (file.exists() && !file.delete()) Diag.record(TAG, "could not delete ${file.name}")
    }

    override fun close() {
        runCatching { raf.close() }
    }

    private companion object {
        const val TAG = "PcmStore"
    }
}
