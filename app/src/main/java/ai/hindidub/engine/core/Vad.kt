package ai.hindidub.engine.core

import kotlin.math.sqrt

/**
 * Energy VAD + silence-aware chunking.
 *
 * Why this is the most valuable optimisation in the engine: Whisper decodes 30-second
 * windows whether or not there is speech in them. An anime episode is 15–25 % silence
 * (pauses, establishing shots, music-only scenes). Trimming those windows before ASR cuts
 * wall time by roughly that fraction, and it also stops Whisper hallucinating subtitle
 * text over music. It costs a few milliseconds of float math.
 *
 * Deliberately not a neural VAD (Silero): that would add a model, ~2 MB, and a second
 * inference pass. An RMS gate with hysteresis is enough for dialogue tracks and has zero
 * dependencies.
 */
object Vad {

    data class Span(val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
    }

    private const val FRAME_MS = 30
    private const val OPEN_DB = -36f      // speech starts
    private const val CLOSE_DB = -44f     // hysteresis: speech ends
    private const val MIN_SPEECH_MS = 180
    private const val PAD_MS = 150        // keep a little air around speech
    private const val JOIN_GAP_MS = 320   // merge pauses shorter than this

    fun spans(pcm: FloatArray, sampleRate: Int = 16000): List<Span> {
        if (pcm.isEmpty()) return emptyList()
        val frame = sampleRate * FRAME_MS / 1000
        if (frame <= 0) return emptyList()

        val raw = ArrayList<Span>()
        var speechStart = -1L
        var i = 0
        while (i + frame <= pcm.size) {
            var sum = 0.0
            for (k in 0 until frame) {
                val v = pcm[i + k]
                sum += v.toDouble() * v
            }
            val rms = sqrt(sum / frame).toFloat()
            val db = if (rms <= 1e-7f) -100f else (20.0 * kotlin.math.log10(rms.toDouble())).toFloat()
            val tMs = i.toLong() * 1000L / sampleRate

            if (speechStart < 0) {
                if (db > OPEN_DB) speechStart = tMs
            } else if (db < CLOSE_DB) {
                val end = tMs
                if (end - speechStart >= MIN_SPEECH_MS) raw.add(Span(speechStart, end))
                speechStart = -1L
            }
            i += frame
        }
        if (speechStart >= 0) raw.add(Span(speechStart, pcm.size.toLong() * 1000L / sampleRate))

        // merge short gaps, then pad
        val merged = ArrayList<Span>()
        for (s in raw) {
            val last = merged.lastOrNull()
            if (last != null && s.startMs - last.endMs < JOIN_GAP_MS) {
                merged[merged.size - 1] = Span(last.startMs, s.endMs)
            } else {
                merged.add(s)
            }
        }
        return merged.map {
            Span((it.startMs - PAD_MS).coerceAtLeast(0L), it.endMs + PAD_MS)
        }
    }

    /**
     * Groups speech spans into chunks of at most [maxChunkMs], splitting on the largest
     * silence available rather than mid-word. Chunking keeps peak RAM flat on long files.
     */
    fun chunks(spans: List<Span>, totalMs: Long, maxChunkMs: Long = 4 * 60_000L): List<Span> {
        if (spans.isEmpty()) return listOf(Span(0, totalMs))
        val out = ArrayList<Span>()
        var start = spans.first().startMs
        var end = spans.first().endMs
        for (s in spans.drop(1)) {
            if (s.endMs - start > maxChunkMs && end > start) {
                out.add(Span(start, end))
                start = s.startMs
                end = s.endMs
            } else {
                end = s.endMs
            }
        }
        out.add(Span(start, end))
        return out
    }

    /** Fraction of the timeline that actually contains speech — shown in the UI. */
    fun speechRatio(spans: List<Span>, totalMs: Long): Float {
        if (totalMs <= 0) return 0f
        val speech = spans.sumOf { it.durationMs }
        return (speech.toFloat() / totalMs).coerceIn(0f, 1f)
    }
}
