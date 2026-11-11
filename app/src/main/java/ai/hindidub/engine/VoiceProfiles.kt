package ai.hindidub.engine

/** Simple, honest voice shaping: the system TTS pitch/rate controls, no cloning. */
data class VoiceProfile(
    val id: String,
    val label: String,
    val pitch: Float,
    val rate: Float
)

object VoiceProfiles {
    val all = listOf(
        VoiceProfile("natural", "Natural", 1.00f, 1.00f),
        VoiceProfile("deep", "Deep male", 0.82f, 0.94f),
        VoiceProfile("bright", "Bright female", 1.18f, 1.03f),
        VoiceProfile("narrator", "Narrator", 0.92f, 0.90f),
        VoiceProfile("energetic", "Energetic", 1.08f, 1.12f)
    )

    fun byId(id: String): VoiceProfile = all.firstOrNull { it.id == id } ?: all[0]
}
