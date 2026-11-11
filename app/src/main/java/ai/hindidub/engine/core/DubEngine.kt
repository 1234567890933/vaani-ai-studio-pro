package ai.hindidub.engine.core

import ai.hindidub.cloud.CloudTranslator
import ai.hindidub.core.DubLanguage
import ai.hindidub.core.Diag
import ai.hindidub.core.EngineRouter
import ai.hindidub.core.LanguageCatalog
import ai.hindidub.core.ResourceGovernor
import ai.hindidub.engine.AudioExtractor
import ai.hindidub.engine.AudioMixer
import ai.hindidub.engine.AudioUtils
import ai.hindidub.engine.AsrProcessor
import ai.hindidub.engine.EngineManager
import ai.hindidub.engine.StyleGlossary
import ai.hindidub.engine.SystemTtsProcessor
import ai.hindidub.engine.TimeStretcher
import ai.hindidub.engine.TranslationEngine
import ai.hindidub.engine.VideoMuxer
import ai.hindidub.engine.VoiceProfiles
import ai.hindidub.engine.audio.PcmStore
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * DubEngine — the scheduling engine.
 *
 * The design constraint that drives everything: **memory must not scale with video length.**
 * A 4 GB phone running a 1 GB translation model has roughly 1.5 GB to play with, and v4 held
 * the whole soundtrack twice in RAM (boxed floats, stereo bed + mono ASR track) which is how
 * an episode turns into an OOM.
 *
 * So the engine works in windows and keeps audio on disk:
 *
 *   for each 4-minute window:
 *       decode 16 kHz mono window   →  VAD  →  Whisper  →  translate  →  TTS
 *                                    →  write each clip into the voice timeline (PCM16 file)
 *   then:  original bed → PCM16 file, mixed with the voice block-by-block
 *   then:  AAC encode from file → mux
 *
 * Peak RAM is one window (~15 MB) plus the models, no matter how long the film is.
 *
 * The other five decisions (all in ENGINE.md): models load once per session; VAD before ASR;
 * silence-aware chunking; dedupe of repeated lines; thermal pacing between segments — plus a
 * deliberate refusal to run stages in parallel, because on 2 big + 6 little cores that is
 * slower and doubles RAM.
 */
class DubEngine(
    private val context: Context,
    private val engineManager: EngineManager
) {

    enum class Stage { PROBE, ASR, TRANSLATE, TTS, MIX, MUX, DONE }

    data class Config(
        val input: Uri,
        val src: DubLanguage,
        val tgt: DubLanguage,
        val voiceProfileId: String = "natural",
        val bedGainDb: Float = -14f,
        val suppressCenter: Boolean = true,
        val translationMode: String = "offline",
        val routingMode: String = "parallel",
        val providerOrder: String = "",
        /** 0 = whole file; 60 = "quick dub" of the first minute. */
        val quickSeconds: Int = 0,
        /** Output audio sample rate: 24000, 32000 or 44100. */
        val mixSampleRate: Int = 24000,
        /** AAC bitrate for the dubbed track. */
        val aacKbps: Int = 96,
        /** Hybrid routing inputs; the router turns these into a per-stage plan. */
        val cloudEnabled: Boolean = false,
        val networkAvailable: Boolean = true,
        val configuredProviders: String = "",
        val cloudTtsProvider: String = ""
    )

    data class Progress(
        val stage: Stage,
        val fraction: Float,
        val message: String,
        val etaMs: Long = -1L
    )

    data class Stats(
        val windows: Int,
        val segments: Int,
        val speechRatio: Float,
        val asrRtf: Float,
        val mtMs: Long,
        val ttsMs: Long,
        val cache: String,
        val moods: String,
        val director: String,
        val threads: Int,
        val peakWindowMb: Int,
        val mixGain: Float
    )

    data class Result(
        val output: File,
        val segments: Int,
        val elapsedMs: Long,
        val stats: Stats,
        /** Normalised waveform peaks of the dubbed track, for the player. */
        val peaks: FloatArray
    )

    private var asr: AsrProcessor? = null
    private var translator: TranslationEngine? = null
    private val cache = SegmentCache()
    private var mixSr = 24000

    private fun asr(): AsrProcessor = asr ?: engineManager.buildAsr().also {
        asr = it
        Log.i(TAG, "ASR resident")
    }

    private fun translator(): TranslationEngine =
        translator ?: engineManager.buildTranslator().also {
            translator = it
            Log.i(TAG, "MT resident")
        }

    fun release() {
        runCatching { asr?.close() }
        runCatching { translator?.close() }
        asr = null
        translator = null
        cache.clear()
        Log.i(TAG, "released")
    }

    suspend fun run(cfg: Config, onProgress: (Progress) -> Unit): Result =
        withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            mixSr = cfg.mixSampleRate
            val work = File(context.cacheDir, "dub_$started").apply { mkdirs() }
            val governor = ResourceGovernor(context)
            val plan = EngineRouter.plan(
                EngineRouter.Request(
                    cloudEnabled = cfg.cloudEnabled,
                    networkAvailable = cfg.networkAvailable,
                    configuredProviders = cfg.configuredProviders,
                    cloudTtsProvider = cfg.cloudTtsProvider.ifBlank { null }
                )
            )
            Diag.record("Engine", "plan ${plan.summary()}")
            val glossary = StyleGlossary(File(context.filesDir, "style_glossary.json"))
                .apply { load() }

            var voice: PcmStore? = null
            var bed: PcmStore? = null
            var mixed: PcmStore? = null

            try {
                // ---------- probe ------------------------------------------
                report(onProgress, Stage.PROBE, 0f, "Reading the file")
                val info = AudioExtractor.probe(context, cfg.input)
                val fullMs = if (info.durationMs > 0) info.durationMs
                else 10 * 60_000L
                val totalMs = if (cfg.quickSeconds > 0)
                    minOf(fullMs, cfg.quickSeconds * 1000L) else fullMs
                val totalFrames = totalMs * mixSr / 1000L
                Log.i(TAG, "duration ${fullMs / 1000}s, processing ${totalMs / 1000}s, " +
                    "${info.sampleRate}Hz/${info.channels}ch")

                val voiceStore = PcmStore(File(work, "voice.pcm"), mixSr, 1).apply {
                    ensureFrames(totalFrames)
                }
                voice = voiceStore

                // ---------- windows ---------------------------------------
                val windows = ArrayList<Pair<Long, Long>>()
                var winStart = 0L
                while (winStart < totalMs) {
                    val winEnd = minOf(winStart + AudioExtractor.WINDOW_MS, totalMs)
                    windows.add(winStart to winEnd)
                    winStart = winEnd
                }

                var baseThreads = engineManager.threads
                var asrMs = 0L
                var mtMs = 0L
                var ttsMs = 0L
                var speechMs = 0L
                var segmentsTotal = 0
                var peakWindowMb = 0
                var mixGain = 1f
                var shapedLines = 0
                var keptOriginal = 0
                val moodCounts = HashMap<String, Int>()
                var totalSpeech = 0L

                val tts = SystemTtsProcessor(context)
                val profile = VoiceProfiles.byId(cfg.voiceProfileId)
                val cloudVoiceKey = if (plan.tts.isCloud) {
                    ai.hindidub.core.SecretStore.get(
                        context,
                        ai.hindidub.cloud.CloudTranslator.keyName(plan.tts.providerId ?: "")
                    )
                } else null
                if (!tts.init(cfg.tgt.tts, profile) && cloudVoiceKey == null) {
                    tts.close()
                    error("No offline voice for ${cfg.tgt.englishName}. Install it in Settings → Text-to-speech.")
                }
                val scratch = File(work, "seg.wav")

                tts.use { speaker ->
                    windows.forEachIndexed { wi, (wFrom, wTo) ->
                        coroutineContext.ensureActive()

                        // 1. decode this window only
                        val pcm = AudioExtractor.windowMono16k(context, cfg.input, wFrom, wTo)
                        if (pcm.isEmpty()) return@forEachIndexed
                        peakWindowMb = max(peakWindowMb, pcm.size * 4 / (1024 * 1024))

                        // 2. VAD — skip windows with no dialogue at all
                        val spans = Vad.spans(pcm, AudioExtractor.TARGET_SR)
                        val speech = spans.sumOf { it.durationMs }
                        totalSpeech += speech
                        speechMs += speech
                        if (speech < 500L) {
                            report(
                                onProgress, Stage.ASR,
                                progressOf(wi, windows.size, 0.75f),
                                "Window ${wi + 1}: no dialogue, skipped"
                            )
                            return@forEachIndexed
                        }

                        // 3. ASR on the window
                        val asrStart = System.currentTimeMillis()
                        val raw = asr().transcribe(
                            pcm,
                            LanguageCatalog.whisperCode(cfg.src.code),
                            baseThreads
                        )
                        asrMs += System.currentTimeMillis() - asrStart
                        if (raw.isEmpty()) return@forEachIndexed

                        val segments = raw.map {
                            it.copy(
                                startMs = it.startMs + wFrom,
                                endMs = it.endMs + wFrom
                            )
                        }
                        segmentsTotal += segments.size
                        report(
                            onProgress, Stage.ASR,
                            progressOf(wi, windows.size, 0.75f),
                            "Window ${wi + 1}/${windows.size}: ${segments.size} lines"
                        )

                        // 4. translate (deduplicated across the whole job)
                        val mtStart = System.currentTimeMillis()
                        val unique = LinkedHashMap<String, String>()
                        for (s in segments) {
                            val key = s.text.trim()
                            if (key.isEmpty()) continue
                            if (!unique.containsKey(key)) unique[key] = cache.translated(key) ?: ""
                        }
                        val todo = unique.filterValues { it.isEmpty() }.keys.toList()
                        if (todo.isNotEmpty()) {
                            val out = translate(todo, cfg) { f ->
                                report(
                                    onProgress, Stage.TRANSLATE,
                                    progressOf(wi, windows.size, 0.75f) * 0.5f + f * 0.25f,
                                    "Translating window ${wi + 1}"
                                )
                            }
                            out.forEachIndexed { i, text -> unique[todo[i]] = text }
                        }
                        val lines = segments.map { glossary.apply(unique[it.text.trim()] ?: it.text) }
                        mtMs += System.currentTimeMillis() - mtStart

                        // 5. voice each line into the timeline
                        val ttsStart = System.currentTimeMillis()
                        segments.forEachIndexed { i, seg ->
                            coroutineContext.ensureActive()
                            baseThreads = governor.checkpoint(engineManager.threads) { msg ->
                                report(onProgress, Stage.TTS, progressOf(wi, windows.size, 0.75f), msg)
                            }
                            // Read how the line was delivered in the original, then shape
                            // the Hindi take to match it.
                            val segFrom = ((seg.startMs - wFrom) * AudioExtractor.TARGET_SR / 1000L)
                                .toInt().coerceIn(0, pcm.size)
                            val segTo = ((seg.endMs - wFrom) * AudioExtractor.TARGET_SR / 1000L)
                                .toInt().coerceIn(segFrom, pcm.size)
                            val prosody = ProsodyAnalyzer.analyze(
                                pcm.copyOfRange(segFrom, segTo), AudioExtractor.TARGET_SR
                            )
                            moodCounts[prosody.label] = (moodCounts[prosody.label] ?: 0) + 1

                            // The director decides pacing, emphasis and whether a line is worth
                            // voicing at all; the planner then decides where it sits in time.
                            val decision = DirectorAgent.decide(
                                DirectorAgent.Line(
                                    text = lines[i],
                                    windowMs = (seg.endMs - seg.startMs).coerceAtLeast(1L),
                                    prosody = prosody
                                )
                            )
                            speaker.setProsody(
                                prosody.pitchScale,
                                (prosody.rateScale * decision.rate).coerceIn(0.5f, 2f)
                            )

                            val line = lines[i]
                            val cacheKey = prosody.label + "|" + line
                            var clip = cache.audio(cacheKey)
                            var clipRate = speaker.sampleRate
                            if (clip == null) {
                                // Cloud voice first when the router says so; any failure falls
                                // straight through to the on-device voice.
                                if (cloudVoiceKey != null) {
                                    runCatching {
                                        ai.hindidub.cloud.CloudTts.synthesize(
                                            line, cloudVoiceKey, lang = cfg.tgt
                                        )
                                    }.getOrNull()?.let { c ->
                                        clip = c.pcm
                                        clipRate = c.sampleRate
                                    }
                                }
                                if (clip == null) clip = speaker.synthesizeToPcm(line, scratch)
                                if (clip != null && clip.isNotEmpty()) cache.putAudio(cacheKey, clip)
                            }
                            if (clip != null && clip.isNotEmpty() && !decision.keepOriginal) {
                                val at24k = AudioUtils.resampleLinear(clip, clipRate, mixSr)
                                val windowMs = (seg.endMs - seg.startMs).coerceAtLeast(120L)
                                val placement = LipSyncPlanner.place(
                                    windowMs = windowMs,
                                    clipMs = (at24k.size * 1000L / mixSr)
                                )
                                val targetLen = (placement.targetMs.toLong() * mixSr / 1000L)
                                    .toInt().coerceAtLeast(mixSr / 4)
                                var fitted = TimeStretcher.fit(at24k, targetLen)
                                if (decision.emphasis != 1f) {
                                    for (k in fitted.indices) {
                                        fitted[k] = (fitted[k] * decision.emphasis).coerceIn(-1f, 1f)
                                    }
                                }
                                val offset = (seg.startMs + placement.leadMs) * mixSr / 1000L
                                voiceStore.writeMono(offset, fitted, fitted.size)
                                if (placement.reason != "natural fit") {
                                    shapedLines++
                                }
                            } else if (decision.keepOriginal) {
                                keptOriginal++
                            }
                        }
                        ttsMs += System.currentTimeMillis() - ttsStart
                        report(
                            onProgress, Stage.TTS,
                            progressOf(wi, windows.size, 0.75f),
                            "Window ${wi + 1}/${windows.size} voiced"
                        )
                    }
                }

                if (segmentsTotal == 0) error("No speech recognised — check the audio track")

                // ---------- bed + mix -------------------------------------
                val finalAudio: PcmStore
                if (cfg.bedGainDb <= -59f) {
                    finalAudio = voiceStore
                } else {
                    report(onProgress, Stage.MIX, 0.75f, "Reading the original sound bed")
                    val channels = if (info.channels >= 2) 2 else 1
                    val bedStore = PcmStore(File(work, "bed.pcm"), mixSr, channels).apply {
                        ensureFrames(totalFrames)
                    }
                    bed = bedStore
                    var bedFrom = 0L
                    while (bedFrom < totalMs) {
                        coroutineContext.ensureActive()
                        val bedTo = minOf(bedFrom + AudioExtractor.WINDOW_MS, totalMs)
                        AudioExtractor.writeBedWindow(context, cfg.input, bedStore, bedFrom, bedTo)
                        bedFrom = bedTo
                    }

                    val mixedStore = PcmStore(File(work, "mixed.pcm"), mixSr, 1).apply {
                        ensureFrames(totalFrames)
                    }
                    mixed = mixedStore
                    mixGain = AudioMixer.streamMix(
                        voiceStore, bedStore, mixedStore, totalFrames,
                        AudioMixer.MixConfig(
                            cfg.bedGainDb, cfg.suppressCenter, info.channels < 2
                        )
                    ) { f ->
                        report(onProgress, Stage.MIX, 0.75f + f * 0.15f, "Mixing")
                    }
                    finalAudio = mixedStore
                }

                // ---------- mux -------------------------------------------
                report(onProgress, Stage.MUX, 0.9f, "Writing the video")
                val movies = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                    ?: context.filesDir
                val outDir = File(movies, "dubbed").apply { mkdirs() }
                val outFile = File(outDir, "dubbed_$started.mp4")

                val peaks = peaksOf(finalAudio, totalFrames)
                retry("MUX") {
                    VideoMuxer.muxFromStore(
                        context.contentResolver, cfg.input, finalAudio, outFile, cfg.aacKbps
                    )
                    outFile
                }
                report(onProgress, Stage.DONE, 1f, "Saved ${outFile.name}")

                val ratio = if (totalMs > 0) totalSpeech.toFloat() / totalMs else 0f
                Result(
                    output = outFile,
                    segments = segmentsTotal,
                    elapsedMs = System.currentTimeMillis() - started,
                    stats = Stats(
                        windows = windows.size,
                        segments = segmentsTotal,
                        speechRatio = ratio,
                        asrRtf = asrMs.toFloat() / max(1L, speechMs),
                        mtMs = mtMs,
                        ttsMs = ttsMs,
                        cache = cache.stats(),
                        moods = moodCounts.entries.sortedByDescending { it.value }
                            .joinToString(" · ") { "${it.key} ${it.value}" }
                            .ifBlank { "calm" },
                        director = "$shapedLines shaped · $keptOriginal kept original",
                        threads = engineManager.threads,
                        peakWindowMb = peakWindowMb,
                        mixGain = mixGain
                    ),
                    peaks = peaks
                )
            } finally {
                runCatching { voice?.close() }
                runCatching { bed?.close() }
                runCatching { mixed?.close() }
                runCatching { work.deleteRecursively() }
            }
        }

    // ---- helpers ----------------------------------------------------------

    private suspend fun translate(
        texts: List<String>,
        cfg: Config,
        onProgress: (Float) -> Unit
    ): List<String> {
        if (cfg.translationMode == "cloud" && cfg.cloudEnabled && cfg.networkAvailable) {
            try {
                val router = CloudTranslator(context)
                val mode = when (cfg.routingMode) {
                    "single" -> CloudTranslator.Mode.SINGLE
                    "failover" -> CloudTranslator.Mode.FAILOVER
                    else -> CloudTranslator.Mode.PARALLEL
                }
                return router.translateAll(
                    texts, cfg.src, cfg.tgt, mode, cfg.providerOrder, onProgress
                )
            } catch (t: Throwable) {
                Log.w(TAG, "cloud failed → on-device: ${t.message}")
            }
        }
        return retry("TRANSLATE") {
            translator().translateAll(texts, cfg.src.code, cfg.tgt.code, onProgress)
        }
    }

    private suspend fun <T> retry(stage: String, block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                return block()
            } catch (t: Throwable) {
                last = t
                Log.w(TAG, "$stage attempt ${attempt + 1}: ${t.message}")
                if (attempt == 0) delay(1200)
            }
        }
        throw last ?: IllegalStateException("$stage failed")
    }

    /** Reads the finished audio back in blocks and reduces it to N normalised peaks. */
    private fun peaksOf(store: PcmStore, totalFrames: Long, buckets: Int = 320): FloatArray {
        val out = FloatArray(buckets)
        if (totalFrames <= 0) return out
        var frame = 0L
        while (frame < totalFrames) {
            val n = minOf(4096L, totalFrames - frame).toInt()
            val data = store.readFrames(frame, n)
            var peak = 0f
            for (v in data) {
                val a = kotlin.math.abs(v)
                if (a > peak) peak = a
            }
            val b0 = (frame * buckets / totalFrames).toInt().coerceIn(0, buckets - 1)
            val b1 = ((frame + n) * buckets / totalFrames).toInt().coerceIn(b0, buckets - 1)
            for (i in b0..b1) if (peak > out[i]) out[i] = peak
            frame += n
        }
        val max = out.maxOrNull() ?: 0f
        if (max > 0f) for (i in out.indices) out[i] = (out[i] / max).coerceIn(0f, 1f)
        return out
    }

    private fun progressOf(index: Int, total: Int, ceiling: Float): Float =
        if (total <= 0) 0f else ((index + 1f) / total) * ceiling

    private fun report(cb: (Progress) -> Unit, stage: Stage, f: Float, msg: String) =
        cb(Progress(stage, f.coerceIn(0f, 1f), msg))

    private companion object {
        const val TAG = "DubEngine"
    }
}
