package ai.hindidub.core

/**
 * Decides, per pipeline stage, whether to run locally or through a cloud provider.
 *
 * This is the "Core Engine Router" from the blueprint. It is pure logic on purpose: the whole
 * decision matrix is unit-tested, and the engine only executes the plan. Rules, in order:
 *
 *  1. cloud off, or no network, or no key for a stage → that stage runs locally;
 *  2. a stage with a configured key runs in the cloud;
 *  3. ASR stays local unless an explicit cloud ASR provider is configured — uploading a film's
 *     audio is a different privacy decision from uploading its text, so it is opt-in.
 *
 * The result is a plain data class, so a failed cloud call can be handled by re-running the
 * stage locally without re-planning anything.
 */
object EngineRouter {

    enum class Mode { LOCAL, CLOUD }

    data class StagePlan(val mode: Mode, val providerId: String? = null) {
        val isCloud: Boolean get() = mode == Mode.CLOUD
    }

    data class Plan(val asr: StagePlan, val mt: StagePlan, val tts: StagePlan) {
        val anyCloud: Boolean get() = asr.isCloud || mt.isCloud || tts.isCloud
        fun summary(): String =
            "asr=${asr.mode.name.lowercase()} mt=${mt.providerId ?: "local"} tts=${tts.providerId ?: "local"}"
    }

    data class Request(
        val cloudEnabled: Boolean,
        val networkAvailable: Boolean,
        /** Provider ids with a key stored, comma separated (the prefs value). */
        val configuredProviders: String,
        val cloudAsrProvider: String? = null,
        val cloudTtsProvider: String? = null
    )

    fun plan(request: Request): Plan {
        val local = Plan(StagePlan(Mode.LOCAL), StagePlan(Mode.LOCAL), StagePlan(Mode.LOCAL))
        if (!request.cloudEnabled || !request.networkAvailable) return local

        val configured = request.configuredProviders
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val mtProvider = configured.firstOrNull()
        val ttsProvider = request.cloudTtsProvider?.takeIf { it in configured }

        return Plan(
            asr = request.cloudAsrProvider
                ?.takeIf { it in configured }
                ?.let { StagePlan(Mode.CLOUD, it) }
                ?: local.asr,
            mt = mtProvider?.let { StagePlan(Mode.CLOUD, it) } ?: local.mt,
            tts = ttsProvider?.let { StagePlan(Mode.CLOUD, it) } ?: local.tts
        )
    }
}
