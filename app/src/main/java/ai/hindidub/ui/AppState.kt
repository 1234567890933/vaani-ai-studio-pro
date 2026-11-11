package ai.hindidub.ui

import ai.hindidub.core.StorageManager

/** The four screens. Kept as a sealed interface so navigation is exhaustive at compile time. */
sealed interface Screen {
    data object Home : Screen
    data object Processing : Screen
    data object Player : Screen
    data object Settings : Screen
    data object Api : Screen
}

data class UiSettings(
    val sourceLang: String = "en",
    val targetLang: String = "hi",
    val voiceProfileId: String = "natural",
    val bedGainDb: Float = -14f,
    val suppressCenter: Boolean = true,
    val asrGroupId: String = "asr-medium",
    val mtGroupId: String = "mt-nllb",
    val translationMode: String = "offline",
    val routingMode: String = "parallel",
    val providerOrder: String = "",
    val threads: Int = 4,
    val quotaGb: Int = 8,
    val mixSampleRate: Int = 24000,
    val aacKbps: Int = 96,
    val themeMode: String = "system",
    val quickSeconds: Int = 0,
    val cloudEnabled: Boolean = false,
    val cloudTtsProvider: String = ""
)

data class UiState(
    val screen: Screen = Screen.Home,
    val settings: UiSettings = UiSettings(),
    val statusLine: String = "",
    val deviceLine: String = "",
    val poolSummary: String = "",
    val modelsReady: Boolean = false,
    val voiceReady: Boolean = false,
    val inputUri: android.net.Uri? = null,
    val inputName: String = "",
    val busy: Boolean = false,
    val busyLabel: String = "",
    val busyFraction: Float = 0f,
    val running: Boolean = false,
    val stage: String = "",
    val stageMessage: String = "",
    val fraction: Float = 0f,
    val etaMs: Long = -1,
    val lastStats: String = "",
    val outputPath: String? = null,
    val peaks: FloatArray? = null,
    val outputs: List<StorageManager.Output> = emptyList(),
    val storageTotal: Long = 0,
    val queueCount: Int = 0,
    val providerStatus: List<String> = emptyList(),
    val notice: String? = null,
    val networkOnline: Boolean = true,
    val planSummary: String = "asr=local mt=local tts=local",
    val keyPresent: Map<String, Boolean> = emptyMap()
)
