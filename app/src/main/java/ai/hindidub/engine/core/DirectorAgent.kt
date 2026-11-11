package ai.hindidub.engine.core

/**
 * A tiny on-device "director" that makes one decision per line, on the CPU, with no model.
 *
 * What it decides, and why a rule set is the right tool here: for each subtitle segment it knows
 * the original window length, the translated text and the prosody of the source delivery. From
 * those three numbers it can tell whether the Hindi line will overrun its window, whether it has
 * room to breathe, and whether the line is too short to be worth voicing at all. A learned model
 * would need training data per language pair to beat a handful of thresholds, and would cost
 * RAM that the ASR needs.
 *
 * The decisions it returns are deliberately conservative: pacing stays inside ±12 %, because
 * system TTS starts to sound metallic beyond that, and a line shorter than ~320 ms is left in
 * the original language rather than replaced with a clipped syllable.
 */
object DirectorAgent {

    data class Line(
        val text: String,
        val windowMs: Long,
        val prosody: ProsodyAnalyzer.Prosody
    )

    data class Decision(
        /** Multiplier applied on top of the prosody rate. */
        val rate: Float,
        /** Linear gain for this clip, 1.0 = unchanged. */
        val emphasis: Float,
        /** True when the original audio should stay audible for this segment. */
        val keepOriginal: Boolean,
        /** Short human-readable reason, shown in the engine panel. */
        val reason: String
    )

    /** Average duration of a spoken word at neutral pace, milliseconds. */
    private const val MS_PER_WORD = 360L
    private const val MIN_VOICEABLE_MS = 320L

    fun decide(line: Line): Decision {
        val words = line.text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        if (words == 0 || line.windowMs < MIN_VOICEABLE_MS) {
            return Decision(1f, 1f, true, "too short to voice — kept original")
        }

        val estimatedMs = words * MS_PER_WORD
        val load = estimatedMs.toFloat() / line.windowMs.toFloat()

        var rate = 1f
        var reason = "natural fit"
        when {
            load > 1.15f -> {
                rate = 1.10f
                reason = "tight window — faster"
            }
            load < 0.70f -> {
                rate = 0.96f
                reason = "room to breathe — slower"
            }
        }

        var emphasis = 1f
        when (line.prosody.mood) {
            ProsodyAnalyzer.Mood.SHOUT -> {
                rate *= 1.04f
                emphasis = 1.20f
            }
            ProsodyAnalyzer.Mood.TENSE -> {
                rate *= 1.02f
                emphasis = 1.08f
            }
            ProsodyAnalyzer.Mood.WHISPER -> {
                rate *= 0.96f
                emphasis = 0.90f
            }
            ProsodyAnalyzer.Mood.SAD -> {
                rate *= 0.97f
                emphasis = 0.95f
            }
            ProsodyAnalyzer.Mood.CALM -> Unit
        }

        return Decision(
            rate = rate.coerceIn(0.90f, 1.12f),
            emphasis = emphasis.coerceIn(0.85f, 1.25f),
            keepOriginal = false,
            reason = reason
        )
    }
}
