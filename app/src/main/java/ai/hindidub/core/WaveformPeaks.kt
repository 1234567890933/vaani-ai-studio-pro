package ai.hindidub.core

import kotlin.math.abs

/**
 * Waveform peaks for the visualiser.
 *
 * A 24-minute episode is 34 M samples; drawing that is pointless and slow. This reduces any
 * PCM buffer to N normalised buckets (peak per bucket), which is what the canvas needs, and
 * costs one pass. Pure arithmetic, so it is unit-tested.
 */
object WaveformPeaks {

    fun peaks(pcm: FloatArray, buckets: Int): FloatArray {
        if (buckets <= 0) return FloatArray(0)
        if (pcm.isEmpty()) return FloatArray(buckets)

        val out = FloatArray(buckets)
        val per = pcm.size.toFloat() / buckets
        for (b in 0 until buckets) {
            val from = (b * per).toInt().coerceIn(0, pcm.size)
            val to = ((b + 1) * per).toInt().coerceIn(from, pcm.size)
            var peak = 0f
            for (i in from until to) {
                val v = abs(pcm[i])
                if (v > peak) peak = v
            }
            out[b] = peak
        }
        val max = out.maxOrNull() ?: 0f
        if (max > 0f) for (b in out.indices) out[b] = (out[b] / max).coerceIn(0f, 1f)
        return out
    }

    /** Decibel-scaled peaks: closer to what the ear expects in a waveform display. */
    fun logPeaks(pcm: FloatArray, buckets: Int): FloatArray =
        peaks(pcm, buckets).map { p ->
            if (p <= 0.001f) 0f else ((20f * kotlin.math.log10(p.toDouble()) + 60f) / 60f)
                .toFloat().coerceIn(0f, 1f)
        }.toFloatArray()
}
