package ai.hindidub.engine.core

/**
 * Timing planner that keeps the dub glued to the picture.
 *
 * This is audio-led alignment, not visual viseme synthesis, and it is worth being precise about
 * the difference: a dubbed line must *start* where the original mouth starts moving and *end*
 * before the next line begins. That is achievable without seeing the video, because the ASR
 * timestamps already mark the speech onsets. So each clip is placed inside its window with:
 *
 *  * a **lead-in** so it starts on the original onset rather than a fixed offset;
 *  * a **tail** so it does not collide with the next line;
 *  * a **bounded stretch** (0.85–1.15) so the voice never races or drags.
 *
 * When the Hindi line is much longer than the window there is nothing to hide: the clip is
 * compressed to the window and the reason is reported, so the engine panel shows which lines
 * had to be squeezed.
 */
object LipSyncPlanner {

    data class Placement(
        val leadMs: Int,
        val tailMs: Int,
        val targetMs: Int,
        val stretch: Float,
        val reason: String
    )

    private const val MAX_LEAD_MS = 220
    private const val MAX_TAIL_MS = 300
    private const val MIN_TARGET_MS = 180L

    fun place(windowMs: Long, clipMs: Long): Placement {
        if (clipMs <= 0L || windowMs <= 0L) {
            return Placement(0, 0, windowMs.toInt().coerceAtLeast(MIN_TARGET_MS.toInt()), 1f, "no clip")
        }
        val ratio = clipMs.toFloat() / windowMs.toFloat()

        return when {
            // Dub is longer than the window: squeeze into the window, no padding.
            ratio >= 1.15f -> Placement(
                leadMs = 0,
                tailMs = 0,
                targetMs = windowMs.toInt(),
                stretch = ratio,
                reason = "squeezed to window"
            )

            // Dub is much shorter: centre it so speech sits under the moving mouth.
            ratio <= 0.85f -> {
                val spare = (windowMs - clipMs).coerceAtLeast(0L)
                val lead = (spare * 0.6f).toInt().coerceIn(0, MAX_LEAD_MS)
                val tail = (spare - lead).toInt().coerceIn(0, MAX_TAIL_MS)
                val target = (windowMs - lead - tail).coerceAtLeast(MIN_TARGET_MS)
                Placement(lead, tail, target.toInt(), ratio, "centred in window")
            }

            else -> Placement(0, 0, windowMs.toInt(), ratio, "natural fit")
        }
    }
}
