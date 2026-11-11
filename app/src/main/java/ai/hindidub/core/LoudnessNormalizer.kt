package ai.hindidub.core

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Loudness matching for the dubbed track.
 *
 * The mixer already avoids clipping (peak normalisation). This is the other half: matching the
 * *average* level, so a quiet whispered scene does not come out 20 dB below the music. It
 * measures RMS over the loudest 70 % of frames (ignoring pauses), computes one gain to reach a
 * target, and caps that gain so peaks cannot exceed [PEAK_CEILING].
 *
 * Pure arithmetic, unit-tested against synthetic signals.
 */
object LoudnessNormalizer {

    const val TARGET_DBFS = -18f
    const val PEAK_CEILING = 0.97f

    data class Result(val gain: Float, val measuredDb: Float, val applied: Boolean)

    fun measure(pcm: FloatArray, frame: Int = 1024): Float {
        if (pcm.isEmpty()) return -90f
        val frames = ArrayList<Float>(pcm.size / frame + 1)
        var i = 0
        while (i + frame <= pcm.size) {
            var sum = 0.0
            for (k in 0 until frame) {
                val v = pcm[i + k]
                sum += v.toDouble() * v
            }
            frames.add(sqrt(sum / frame).toFloat())
            i += frame
        }
        if (frames.isEmpty()) {
            var sum = 0.0
            for (v in pcm) sum += v.toDouble() * v
            frames.add(sqrt(sum / pcm.size).toFloat())
        }
        frames.sort()
        val from = (frames.size * 0.3f).toInt().coerceIn(0, frames.size - 1)
        var acc = 0.0
        for (k in from until frames.size) acc += frames[k].toDouble()
        val rms = (acc / (frames.size - from)).toFloat()
        return if (rms <= 1e-7f) -90f else (20f * log10(rms.toDouble())).toFloat()
    }

    fun plan(pcm: FloatArray, targetDbfs: Float = TARGET_DBFS): Result {
        val measured = measure(pcm)
        if (measured <= -89f) return Result(1f, measured, false)

        val desired = 10f.pow((targetDbfs - measured) / 20f)
        val peak = pcm.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        val peakCap = if (peak > 0f) PEAK_CEILING / peak else 1f
        val gain = desired.coerceAtMost(peakCap).coerceIn(0.25f, 4f)
        return Result(gain, measured, kotlin.math.abs(gain - 1f) > 0.01f)
    }
}
