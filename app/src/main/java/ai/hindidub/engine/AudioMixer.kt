package ai.hindidub.engine

import ai.hindidub.engine.audio.PcmStore
import ai.hindidub.core.Diag
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Foley retention, now streaming.
 *
 * The original sound bed is kept and ducked under the dub, optionally with centre-channel
 * dialogue suppression. Both inputs are [PcmStore]s on disk and the output is written block
 * by block, so mixing a two-hour film uses a few hundred KB of RAM instead of ~400 MB.
 *
 * [MixConfig.sourceIsMono] matters: on a mono track L and R are identical, so L−R is silence
 * and "centre suppression" would delete the whole bed. v4 had exactly that bug.
 */
object AudioMixer {

    data class MixConfig(
        val bedGainDb: Float = -14f,
        val suppressCenter: Boolean = true,
        val sourceIsMono: Boolean = false,
        val voiceGainDb: Float = 0f
    )

    private const val BLOCK = 24_000
    private const val TARGET_PEAK = 0.98f

    /**
     * Mixes in two passes: the first measures the peak of the *combined* signal, the second
     * writes with one global gain so nothing clips.
     *
     * v5 soft-clipped each sample with tanh, which compresses loud scenes unevenly and colours
     * the whole dub. A single measured gain keeps the level relationship intact: if the mix
     * peaks at 1.25, everything is scaled by 0.79 once, instead of only the loud parts being
     * bent. The extra pass is a read over a temp file — milliseconds.
     *
     * @return the gain applied (1.0 when no normalisation was needed)
     */
    fun streamMix(
        voice: PcmStore,
        bed: PcmStore?,
        out: PcmStore,
        totalFrames: Long,
        cfg: MixConfig,
        onProgress: (Float) -> Unit = {}
    ): Float {
        val bedGain = dbToGain(cfg.bedGainDb)
        val voiceGain = dbToGain(cfg.voiceGainDb)
        val canSuppress = cfg.suppressCenter && bed != null && !cfg.sourceIsMono &&
            bed.channels >= 2

        // ---- pass 1: peak ------------------------------------------------
        var peak = 0f
        var frame = 0L
        while (frame < totalFrames) {
            val n = minOf(BLOCK.toLong(), totalFrames - frame).toInt()
            val v = voice.readFrames(frame, n)
            val b = bed?.readFrames(frame, n) ?: FloatArray(0)
            for (i in 0 until n) {
                val s = combine(v, b, i, bed, bedGain, voiceGain, canSuppress)
                if (abs(s) > peak) peak = abs(s)
            }
            frame += n
            onProgress(0.5f * (frame.toFloat() / totalFrames.coerceAtLeast(1)))
        }

        val gain = if (peak > TARGET_PEAK) TARGET_PEAK / peak else 1f
        Diag.record(
            "Mixer",
            "peak ${"%.3f".format(peak)}, gain ${"%.3f".format(gain)}" +
                (if (gain < 1f) " (${"%.1f".format(20 * log10(gain))} dB)" else "")
        )

        // ---- pass 2: write ----------------------------------------------
        frame = 0L
        while (frame < totalFrames) {
            val n = minOf(BLOCK.toLong(), totalFrames - frame).toInt()
            val v = voice.readFrames(frame, n)
            val b = bed?.readFrames(frame, n) ?: FloatArray(0)
            val mixed = FloatArray(n)
            for (i in 0 until n) {
                val s = combine(v, b, i, bed, bedGain, voiceGain, canSuppress) * gain
                mixed[i] = s.coerceIn(-1f, 1f)
            }
            out.writeFrames(frame, mixed, n)
            frame += n
            onProgress(0.5f + 0.5f * (frame.toFloat() / totalFrames.coerceAtLeast(1)))
        }
        return gain
    }

    private fun combine(
        v: FloatArray,
        b: FloatArray,
        i: Int,
        bed: PcmStore?,
        bedGain: Float,
        voiceGain: Float,
        canSuppress: Boolean
    ): Float {
        val vv = if (i < v.size) v[i] else 0f
        if (bed == null) return vv * voiceGain
        val ch = bed.channels
        val l = if (i * ch < b.size) b[i * ch] else 0f
        val r = if (ch > 1 && i * ch + 1 < b.size) b[i * ch + 1] else l
        val bedSample = if (canSuppress) (l - r) * 0.5f else (l + r) * 0.5f
        return vv * voiceGain + bedSample * bedGain
    }

    fun dbToGain(db: Float): Float = 10f.pow(db / 20f)
}
