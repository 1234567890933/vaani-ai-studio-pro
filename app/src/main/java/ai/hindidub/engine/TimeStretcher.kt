package ai.hindidub.engine

import kotlin.math.roundToInt

/**
 * Fits a synthesised clip into its dubbing window.
 *
 * This uses linear resampling, which changes pitch as well as duration — acceptable for
 * the ±15 % corrections that dominate in practice, audible beyond that. Swap in WSOLA or
 * Sonic if you need pitch preservation (see INTEGRATION_GUIDE.md, section 9).
 */
object TimeStretcher {

    const val MAX_STRETCH = 1.60f
    const val MIN_STRETCH = 0.60f

    /**
     * @param pcm clip at [sampleRate]
     * @param targetSamples desired length in samples at the same rate
     */
    fun fit(pcm: FloatArray, targetSamples: Int): FloatArray {
        if (pcm.isEmpty() || targetSamples <= 0) return FloatArray(targetSamples.coerceAtLeast(0))

        val ratio = targetSamples.toFloat() / pcm.size
        if (ratio in 0.98f..1.02f) {
            return if (pcm.size == targetSamples) pcm else padOrTrim(pcm, targetSamples)
        }
        val clamped = ratio.coerceIn(MIN_STRETCH, MAX_STRETCH)
        val stretched = stretch(pcm, clamped)
        return padOrTrim(stretched, targetSamples)
    }

    private fun stretch(pcm: FloatArray, ratio: Float): FloatArray {
        val outLen = (pcm.size * ratio).roundToInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val step = pcm.size.toDouble() / outLen
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceIn(0, pcm.size - 1)
            val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = pcm[i0] * (1f - frac) + pcm[i1] * frac
        }
        // short fade in/out to avoid clicks at segment boundaries
        val fade = minOf(240, outLen / 8)
        for (i in 0 until fade) {
            val g = i / fade.toFloat()
            out[i] *= g
            out[outLen - 1 - i] *= g
        }
        return out
    }

    private fun padOrTrim(pcm: FloatArray, targetSamples: Int): FloatArray {
        if (pcm.size == targetSamples) return pcm
        val out = FloatArray(targetSamples)
        System.arraycopy(pcm, 0, out, 0, minOf(pcm.size, targetSamples))
        return out
    }
}
