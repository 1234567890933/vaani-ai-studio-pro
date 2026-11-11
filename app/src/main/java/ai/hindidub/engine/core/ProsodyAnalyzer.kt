package ai.hindidub.engine.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Reads how a line is *delivered* from the original audio, so the Hindi take keeps the same
 * feeling instead of sounding flat.
 *
 * What this deliberately is not: a neural emotion classifier. A SER model would add hundreds
 * of MB and still need per-language training data; three cheap measurements carry most of the
 * signal for dubbing:
 *
 *  * **loudness (RMS dB)** separates a whisper from a shout;
 *  * **pitch (autocorrelation)** separates tense/excited delivery from flat or sad delivery;
 *  * **speech rate** (zero-crossing count as a cheap proxy) nudges pacing.
 *
 * Those map to a small set of mood presets, which are applied as pitch/rate/gain multipliers
 * on the TTS voice for that one line. A whispered line comes out quieter and lower, a shout
 * comes out louder and faster — the dub follows the performance rather than fighting it.
 *
 * Everything here is pure arithmetic, which is why it is covered by the JVM self-test.
 */
object ProsodyAnalyzer {

    enum class Mood { CALM, TENSE, SHOUT, WHISPER, SAD }

    data class Prosody(
        val rmsDb: Float,
        val pitchHz: Float,
        val mood: Mood,
        val pitchScale: Float,
        val rateScale: Float,
        val gainDb: Float
    ) {
        val label: String get() = mood.name.lowercase()
    }

    private const val MIN_PITCH_HZ = 70.0
    private const val MAX_PITCH_HZ = 400.0

    fun analyze(pcm: FloatArray, sampleRate: Int): Prosody {
        if (pcm.isEmpty() || sampleRate <= 0) {
            return Prosody(-90f, 0f, Mood.CALM, 1f, 1f, 0f)
        }

        val rms = rmsOf(pcm)
        val rmsDb = if (rms <= 1e-7f) -90f else (20f * log10(rms.toDouble())).toFloat()
        val pitch = pitchHz(pcm, sampleRate)

        val mood = when {
            rmsDb < -42f -> Mood.WHISPER
            rmsDb > -12f -> Mood.SHOUT
            // 250 Hz sits above typical female speech (~165–255 Hz), so this fires on
            // genuinely raised delivery, not on a normal speaking voice.
            pitch >= 250f && rmsDb > -26f -> Mood.TENSE
            pitch in 1f..100f && rmsDb < -24f -> Mood.SAD
            else -> Mood.CALM
        }

        // Multipliers are deliberately small: the goal is to follow the performance, not to
        // caricature it. Anything above ~1.12 pitch starts to sound synthetic on system TTS.
        return when (mood) {
            Mood.WHISPER -> Prosody(rmsDb, pitch, mood, pitchScale = 0.97f, rateScale = 0.94f, gainDb = -1.5f)
            Mood.SHOUT -> Prosody(rmsDb, pitch, mood, pitchScale = 1.06f, rateScale = 1.05f, gainDb = 1.5f)
            Mood.TENSE -> Prosody(rmsDb, pitch, mood, pitchScale = 1.03f, rateScale = 1.02f, gainDb = 0.5f)
            Mood.SAD -> Prosody(rmsDb, pitch, mood, pitchScale = 0.93f, rateScale = 0.93f, gainDb = -1.0f)
            Mood.CALM -> Prosody(rmsDb, pitch, mood, pitchScale = 1f, rateScale = 1f, gainDb = 0f)
        }
    }

    /** RMS of the whole clip, skipping the quietest 10 % so a pause does not dominate. */
    private fun rmsOf(pcm: FloatArray): Float {
        val frame = 256
        if (pcm.size < frame) {
            var sum = 0.0
            for (v in pcm) sum += v.toDouble() * v
            return sqrt(sum / pcm.size).toFloat()
        }
        val frames = ArrayList<Float>(pcm.size / frame)
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
        frames.sort()
        val from = (frames.size * 0.1f).toInt().coerceIn(0, frames.size - 1)
        var acc = 0.0
        for (k in from until frames.size) acc += frames[k].toDouble()
        return (acc / (frames.size - from)).toFloat()
    }

    /**
     * Autocorrelation pitch estimate over the loudest 40 ms window. Returns 0 when the clip is
     * too quiet or has no clear periodicity (whispered or noisy speech).
     */
    private fun pitchHz(pcm: FloatArray, sampleRate: Int): Float {
        val win = minOf(pcm.size, sampleRate * 40 / 1000)
        if (win < 128) return 0f
        val start = (pcm.size - win) / 2
        val minLag = (sampleRate / MAX_PITCH_HZ).toInt().coerceAtLeast(2)
        val maxLag = (sampleRate / MIN_PITCH_HZ).toInt().coerceAtMost(win / 2)
        if (maxLag <= minLag) return 0f

        // Autocorrelation scores every lag, but a lag of 2x (or 3x) the true period scores
        // just as well — the classic octave error. Taking the plain maximum therefore reports
        // half the real pitch. Fix: find the maximum, then choose the *shortest* lag that is
        // within 90 % of it, which is the true fundamental.
        val scores = DoubleArray(maxLag + 1)
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            var norm = 0.0
            for (i in 0 until win - lag) {
                val a = pcm[start + i]
                val b = pcm[start + i + lag]
                sum += a.toDouble() * b
                norm += a.toDouble() * a + b.toDouble() * b
            }
            if (norm <= 1e-9) continue
            val score = sum / norm
            scores[lag] = score
            if (score > bestScore) bestScore = score
        }
        if (bestScore < 0.3) return 0f

        val threshold = bestScore * 0.9
        for (lag in minLag..maxLag) {
            if (scores[lag] >= threshold) {
                return sampleRate.toFloat() / lag
            }
        }
        return 0f
    }
}
