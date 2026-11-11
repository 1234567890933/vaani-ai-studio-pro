// Self-test for the pure-logic half of DubEngine.
//
// It compiles and runs on a plain JVM with no Android SDK, which is how the engine's
// arithmetic, VAD, PCM store, mixer, cache and language tables are actually verified:
//
//   bash tools/jvmtest/run_selftest.sh
//
// Every group runs fixed cases plus randomised iterations, so one run performs thousands of
// assertions. Exit code is non-zero if anything fails.

import ai.hindidub.cloud.JsonArrays
import ai.hindidub.core.Diag
import ai.hindidub.core.EngineRouter
import ai.hindidub.core.LoudnessNormalizer
import ai.hindidub.core.WavReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import ai.hindidub.core.StorageManager
import ai.hindidub.core.WaveformPeaks
import ai.hindidub.core.LanguageCatalog
import ai.hindidub.engine.AudioMixer
import ai.hindidub.engine.AudioUtils
import ai.hindidub.engine.TimeStretcher
import ai.hindidub.engine.audio.FloatSink
import ai.hindidub.engine.audio.PcmStore
import ai.hindidub.engine.core.ProsodyAnalyzer
import ai.hindidub.engine.core.SegmentCache
import ai.hindidub.engine.core.Vad
import ai.hindidub.model.ModelCatalog
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

private val SEED: Int = System.getenv("SEED")?.toIntOrNull() ?: 0

private var checks = 0
private var failures = 0

private fun check(cond: Boolean, msg: String) {
    checks++
    if (!cond) {
        failures++
        println("  FAIL: $msg")
    }
}

private fun near(a: Float, b: Float, tol: Float, msg: String) =
    check(abs(a - b) <= tol, "$msg (got $a, want $b ±$tol)")

private fun group(name: String, block: () -> Unit) {
    val before = failures
    block()
    println((if (failures == before) "PASS  " else "FAIL  ") + name)
}

private fun tone(frames: Int, sr: Int = 16000, amp: Float = 0.3f, hz: Double = 220.0): FloatArray {
    val out = FloatArray(frames)
    for (i in 0 until frames) out[i] = (amp * sin(2.0 * Math.PI * hz * i / sr)).toFloat()
    return out
}

private fun silence(frames: Int) = FloatArray(frames)

private fun concat(vararg parts: FloatArray): FloatArray {
    val total = parts.sumOf { it.size }
    val out = FloatArray(total)
    var o = 0
    for (p in parts) {
        System.arraycopy(p, 0, out, o, p.size)
        o += p.size
    }
    return out
}

private fun tmpDir(): File =
    File(System.getProperty("java.io.tmpdir"), "hindidub-test-${System.nanoTime()}").apply { mkdirs() }

fun main() {
    println("DubEngine self-test")
    println("==================")

    group("FloatSink") {
        val s = FloatSink(2)
        for (i in 0 until 100) s.add(i.toFloat())
        check(s.size == 100, "size after 100 adds")
        val a = s.toArray()
        check(a.size == 100 && a[99] == 99f, "contents preserved through growth")

        val s2 = FloatSink(4)
        s2.addAll(floatArrayOf(1f, 2f, 3f))
        check(s2.size == 3 && s2.toArray()[2] == 3f, "addAll")

        val s3 = FloatSink(4)
        s3.addDownmixed(floatArrayOf(0.5f, -0.5f, 1f, 0f), 2, 2)
        val d = s3.toArray()
        check(d.size == 2, "downmix frame count")
        near(d[0], 0f, 1e-6f, "downmix stereo pair 1")
        near(d[1], 0.5f, 1e-6f, "downmix stereo pair 2")

        val s4 = FloatSink(4)
        s4.addDownmixed(floatArrayOf(0.25f, 0.75f), 2, 1)
        check(s4.size == 2, "mono passthrough")
    }

    group("AudioUtils.resampleLinear") {
        val pcm = tone(1000, 16000)
        val same = AudioUtils.resampleLinear(pcm, 16000, 16000)
        check(same === pcm, "identity when rates match")

        val up = AudioUtils.resampleLinear(pcm, 16000, 24000)
        near(up.size.toFloat(), 1500f, 1f, "16000→24000 length")

        val down = AudioUtils.resampleLinear(pcm, 24000, 16000)
        near(down.size.toFloat(), 666f, 2f, "24000→16000 length")

        val dc = AudioUtils.resampleLinear(FloatArray(100) { 0.5f }, 8000, 16000)
        for (i in 0 until dc.size step 7) near(dc[i], 0.5f, 1e-4f, "DC preserved at $i")

        val rnd = Random(SEED + 7)
        repeat(200) {
            val n = rnd.nextInt(1, 5000)
            val src = rnd.nextInt(8000, 48000)
            val dst = rnd.nextInt(8000, 48000)
            val out = AudioUtils.resampleLinear(FloatArray(n) { 0.1f }, src, dst)
            val expected = (n.toDouble() * dst / src).toInt().coerceAtLeast(1)
            check(out.size == expected, "resample length n=$n $src→$dst")
            check(out.all { !it.isNaN() }, "no NaN in resample")
        }
        check(AudioUtils.resampleLinear(FloatArray(0), 16000, 24000).isEmpty(), "empty input")
    }

    group("TimeStretcher") {
        val clip = tone(24000, 24000)
        val fit = TimeStretcher.fit(clip, 16000)
        check(fit.size == 16000, "fit shrinks to exact target")
        val grow = TimeStretcher.fit(clip, 48000)
        check(grow.size == 48000, "fit grows to exact target")
        check(TimeStretcher.fit(FloatArray(0), 1000).size == 1000, "empty clip pads to target")
        check(TimeStretcher.fit(clip, 0).isEmpty(), "zero target")

        val rnd = Random(SEED + 11)
        repeat(200) {
            val n = rnd.nextInt(100, 40000)
            val target = rnd.nextInt(100, 40000)
            val out = TimeStretcher.fit(tone(n, 24000), target)
            check(out.size == target, "stretch exact size n=$n target=$target")
            check(out.all { !it.isNaN() && abs(it) <= 1.01f }, "stretch output bounded")
            near(out[0], 0f, 0.5f, "fade-in at start")
        }
    }

    group("Vad.spans") {
        check(Vad.spans(FloatArray(0)).isEmpty(), "empty input")
        check(Vad.spans(silence(16000)).isEmpty(), "pure silence → no spans")

        val burst = concat(
            silence(8000), tone(16000), silence(8000), tone(16000), silence(8000)
        )
        val spans = Vad.spans(burst, 16000)
        check(spans.size == 2, "two bursts → two spans (got ${spans.size})")
        check(spans.all { it.durationMs > 900 }, "each span covers its burst")
        check(spans.all { it.startMs >= 0 && it.endMs > it.startMs }, "spans ordered and valid")

        // pauses shorter than the join threshold must merge
        val shortGap = concat(tone(8000), silence(1600), tone(8000))
        check(Vad.spans(shortGap, 16000).size == 1, "short pause merges into one span")

        val ratio = Vad.speechRatio(spans, burst.size.toLong() * 1000 / 16000)
        check(ratio in 0f..1f, "speech ratio bounded ($ratio)")

        val chunks = Vad.chunks(spans, 600_000, 60_000)
        check(chunks.size >= 1, "chunks produced")
        check(chunks.all { it.endMs > it.startMs }, "chunks valid")
        check(Vad.chunks(emptyList(), 5000).single().endMs == 5000L, "no spans → whole file chunk")

        val rnd = Random(SEED + 3)
        repeat(120) {
            val noise = FloatArray(rnd.nextInt(0, 40000)) {
                (rnd.nextFloat() - 0.5f) * 2f * rnd.nextFloat() * 0.2f
            }
            val s = Vad.spans(noise, 16000)
            check(s.all { it.startMs >= 0 }, "random signal spans non-negative")
            check(s.zipWithNext().all { (a, b) -> b.startMs >= a.endMs }, "spans non-overlapping")
        }
    }

    group("PcmStore") {
        val dir = tmpDir()
        val store = PcmStore(File(dir, "a.pcm"), 24000, 1)
        store.ensureFrames(1000)
        check(store.frameCount() == 1000L, "ensureFrames sets length")

        val block = FloatArray(100) { 0.5f }
        store.writeMono(200, block, 100)
        val read = store.readFrames(200, 100)
        check(read.size == 100, "read back frame count")
        near(read[0], 0.5f, 1f / 32000f, "PCM16 round-trip value")

        val head = store.readFrames(0, 50)
        check(head.all { abs(it) < 1e-6f }, "sparse write leaves silence before")
        val tail = store.readFrames(400, 50)
        check(tail.all { abs(it) < 1e-6f }, "silence after written block")

        val past = store.readFrames(990, 100)
        check(past.size == 10, "read clamps at end of file (got ${past.size})")
        check(store.readFrames(5000, 10).isEmpty(), "read past end returns empty")

        val stereo = PcmStore(File(dir, "b.pcm"), 24000, 2)
        stereo.ensureFrames(10)
        stereo.writeFrames(0, floatArrayOf(0.1f, -0.1f, 0.2f, -0.2f), 2)
        val sread = stereo.readFrames(0, 2)
        check(sread.size == 4, "stereo frame count")
        near(sread[0], 0.1f, 1f / 32000f, "left channel")
        near(sread[1], -0.1f, 1f / 32000f, "right channel")

        val rnd = Random(SEED + 5)
        repeat(60) {
            val frames = rnd.nextInt(1, 3000)
            val start = rnd.nextInt(0, 3000)
            val data = FloatArray(frames) { rnd.nextFloat() * 2f - 1f }
            val st = PcmStore(File(dir, "r$it.pcm"), 24000, 1)
            st.ensureFrames((start + frames).toLong())
            st.writeMono(start.toLong(), data, frames)
            val back = st.readFrames(start.toLong(), frames)
            check(back.size == frames, "random round-trip size $it")
            var worst = 0f
            for (i in 0 until frames) worst = maxOf(worst, abs(back[i] - data[i]))
            check(worst < 1f / 16000f, "random round-trip precision $it (worst $worst)")
            st.closeAndDelete()
        }
        store.closeAndDelete()
        check(!File(dir, "a.pcm").exists(), "closeAndDelete removes file")
    }

    group("AudioMixer") {
        near(AudioMixer.dbToGain(0f), 1f, 1e-5f, "0 dB = unity")
        near(AudioMixer.dbToGain(-6f), 0.501f, 1e-3f, "-6 dB ≈ half")
        near(AudioMixer.dbToGain(-20f), 0.1f, 1e-3f, "-20 dB = tenth")

        val dir = tmpDir()
        // stereo bed: identical channels = pure centre
        val bed = PcmStore(File(dir, "bed.pcm"), 24000, 2).apply { ensureFrames(1000) }
        val centre = FloatArray(1000 * 2)
        for (i in 0 until 1000) {
            centre[i * 2] = 0.5f
            centre[i * 2 + 1] = 0.5f
        }
        bed.writeFrames(0, centre, 1000)
        val voice = PcmStore(File(dir, "voice.pcm"), 24000, 1).apply { ensureFrames(1000) }
        val out = PcmStore(File(dir, "out.pcm"), 24000, 1).apply { ensureFrames(1000) }

        AudioMixer.streamMix(
            voice, bed, out, 1000,
            AudioMixer.MixConfig(bedGainDb = 0f, suppressCenter = true, sourceIsMono = false)
        )
        val suppressed = out.readFrames(0, 1000)
        check(suppressed.all { abs(it) < 1e-3f }, "centre-panned bed removed by suppression")

        out.ensureFrames(1000)
        AudioMixer.streamMix(
            voice, bed, out, 1000,
            AudioMixer.MixConfig(bedGainDb = 0f, suppressCenter = false, sourceIsMono = false)
        )
        near(out.readFrames(0, 10)[0], 0.5f, 2f / 32768f, "no suppression keeps bed")

        out.ensureFrames(1000)
        AudioMixer.streamMix(
            voice, bed, out, 1000,
            AudioMixer.MixConfig(bedGainDb = 0f, suppressCenter = true, sourceIsMono = true)
        )
        near(out.readFrames(0, 10)[0], 0.5f, 2f / 32768f, "mono source keeps bed (v4 bug)")

        // voice on top of bed at -14 dB
        voice.writeMono(0, FloatArray(1000) { 0.4f }, 1000)
        out.ensureFrames(1000)
        AudioMixer.streamMix(
            voice, bed, out, 1000,
            AudioMixer.MixConfig(bedGainDb = -14f, suppressCenter = false, sourceIsMono = false)
        )
        val mixed = out.readFrames(0, 10)[0]
        check(mixed > 0.4f, "voice and bed sum ($mixed)")
        check(mixed <= 1f, "mix clamped to 1.0 ($mixed)")

        out.ensureFrames(1000)
        AudioMixer.streamMix(voice, null, out, 1000, AudioMixer.MixConfig())
        near(out.readFrames(0, 5)[0], 0.4f, 4f / 32768f, "null bed → voice passthrough")

        bed.closeAndDelete()
        voice.closeAndDelete()
        out.closeAndDelete()
    }

    group("SegmentCache") {
        val cache = SegmentCache(maxEntries = 4)
        check(cache.translated("a") == null, "miss before put")
        cache.putTranslation("a", "A")
        check(cache.translated("a") == "A", "hit after put")
        check(cache.translationHits == 1, "hit counter")
        for (i in 0 until 10) cache.putTranslation("k$i", "v$i")
        check(cache.translated("k0") == null, "oldest evicted")
        check(cache.translated("k9") == "v9", "newest retained")

        cache.putAudio("x", FloatArray(100))
        check(cache.audio("x") != null, "audio cached")
        cache.putAudio("long", FloatArray(24000 * 10))
        check(cache.audio("long") == null, "long clip not cached")
        check(cache.stats().contains("cache:"), "stats format")
    }

    group("LanguageCatalog") {
        check(LanguageCatalog.sources.size >= 40, "source list is broad (${LanguageCatalog.sources.size})")
        check(LanguageCatalog.targets.size == 23, "23 Indian targets (${LanguageCatalog.targets.size})")
        check(LanguageCatalog.targets.all { it.isIndic }, "every target is Indic")
        check(LanguageCatalog.sources.map { it.code }.toSet().size == LanguageCatalog.sources.size,
            "source codes unique")
        check(LanguageCatalog.targets.map { it.nllb }.toSet().size == 23, "target tags unique")
        check(LanguageCatalog.sources.all { it.nllb.isNotBlank() }, "every source has an NLLB tag")
        check(LanguageCatalog.sources.all { it.tts.isNotBlank() }, "every source has a TTS locale")
        check(LanguageCatalog.byCode("ja")?.nllb == "jpn_Jpan", "Japanese tag")
        check(LanguageCatalog.whisperCode("ja") == "ja", "whisper code for Japanese")
        check(LanguageCatalog.whisperCode("bho") == "auto", "unsupported whisper code → auto")
        check(LanguageCatalog.whisperCode("zz") == "auto", "unknown language → auto")
        check(LanguageCatalog.targetByCode("ta")?.tts == "ta-IN", "Tamil TTS locale")
        check(LanguageCatalog.sharesVoiceWith(LanguageCatalog.targetByCode("mai")!!)?.code == "hi",
            "Maithili shares the Hindi voice")
        check(LanguageCatalog.popular().any { it.code == "ja" }, "Japanese is in the quick row")
    }

    group("ModelCatalog") {
        check(ModelCatalog.all.size >= 5, "catalogue populated")
        check(ModelCatalog.all.map { it.id }.toSet().size == ModelCatalog.all.size, "ids unique")
        val paths = ModelCatalog.all.flatMap { g -> g.files.map { it.path } }
        check(paths.toSet().size == paths.size, "file paths unique")
        check(ModelCatalog.all.all { g -> g.files.isNotEmpty() }, "every group has files")
        check(ModelCatalog.all.all { g -> g.files.all { it.bytes > 0 } }, "sizes positive")
        check(ModelCatalog.all.all { g -> g.files.all { it.url.startsWith("https://") } }, "https urls")
        check(ModelCatalog.all.all { g -> g.totalBytes == g.files.sumOf { it.bytes } }, "totals add up")
        check(ModelCatalog.all.all { g -> g.tier in setOf("S", "M", "L", "XL") }, "valid tiers")
        check(ModelCatalog.asrGroups.size >= 6, "ASR options (${ModelCatalog.asrGroups.size})")
        check(ModelCatalog.mtGroups.size >= 3, "MT options (${ModelCatalog.mtGroups.size})")
        check(ModelCatalog.poolBytes() > 5L * 1024 * 1024 * 1024, "pool is multi-GB")
        check(ModelCatalog.asrTiny.isBundledOnly, "starter model ships in the APK")
        check(ModelCatalog.asrFor(2000).id == "asr-base", "2 GB phone gets base")
        check(ModelCatalog.asrFor(4000).id == "asr-small", "4 GB phone gets small")
        check(ModelCatalog.asrFor(8000).id == "asr-large", "8 GB phone gets large")
        check(ModelCatalog.mtFor(8000).id == "mt-nllb-fp32", "big RAM gets fp32 MT")
        check(ModelCatalog.byId("mt-nllb")?.files?.size == 4, "NLLB needs 4 files")
    }

    group("JsonArrays") {
        check(JsonArrays.parseStrings("[\"a\",\"b\"]") == listOf("a", "b"), "plain array")
        check(
            JsonArrays.parseStrings("```json\n[\"x\",\"y\"]\n```") == listOf("x", "y"),
            "fenced"
        )
        check(
            JsonArrays.parseStrings("Here you go: [\"p\", \"q\"] hope that helps")
                == listOf("p", "q"),
            "prose around array"
        )
        check(
            JsonArrays.parseStrings("[\"he said \\\"hi\\\"\",\"line\\nbreak\"]")
                == listOf("he said \"hi\"", "line\nbreak"),
            "escapes"
        )
        check(
            JsonArrays.parseStrings("[\"\\u0939\\u093f\"]") == listOf("\u0939\u093f"),
            "unicode escapes"
        )
        check(JsonArrays.parseStrings("no json here").isEmpty(), "no array")
        check(JsonArrays.parseStrings("[]").isEmpty(), "empty array")
        check(
            JsonArrays.parseStrings("[\"a\", 5, \"b\"]") == listOf("a", "b"),
            "non-strings skipped"
        )
    }


    group("ProsodyAnalyzer") {
        val sr = 16000
        val quiet = ProsodyAnalyzer.analyze(tone(8000, sr, amp = 0.004f), sr)
        check(quiet.mood == ProsodyAnalyzer.Mood.WHISPER, "quiet clip → whisper (${quiet.mood})")
        check(quiet.rateScale < 1f, "whisper is slowed down")

        val loud = ProsodyAnalyzer.analyze(tone(8000, sr, amp = 0.9f), sr)
        check(loud.mood == ProsodyAnalyzer.Mood.SHOUT, "loud clip → shout (${loud.mood})")
        check(loud.pitchScale > 1f, "shout is pitched up")

        val normal = ProsodyAnalyzer.analyze(tone(8000, sr, amp = 0.15f), sr)
        check(normal.mood == ProsodyAnalyzer.Mood.CALM, "mid clip → calm (${normal.mood})")
        near(normal.pitchScale, 1f, 1e-4f, "calm is neutral")

        val high = ProsodyAnalyzer.analyze(tone(8000, sr, amp = 0.25f, hz = 260.0), sr)
        check(high.pitchHz > 150f, "pitch estimated for a 260 Hz tone (${high.pitchHz})")
        check(high.mood == ProsodyAnalyzer.Mood.TENSE, "high pitched → tense (${high.mood})")

        val low = ProsodyAnalyzer.analyze(tone(8000, sr, amp = 0.03f, hz = 90.0), sr)
        check(low.pitchHz < 150f, "pitch estimated for a 90 Hz tone (${low.pitchHz})")

        val empty = ProsodyAnalyzer.analyze(FloatArray(0), sr)
        check(empty.mood == ProsodyAnalyzer.Mood.CALM, "empty input is safe")
        near(empty.pitchScale, 1f, 1e-4f, "empty input neutral")

        // every mood must stay inside the range system TTS can render
        listOf(quiet, loud, normal, high, low).forEach { pr ->
            check(pr.pitchScale in 0.9f..1.1f, "pitch scale sane for ${pr.mood} (${pr.pitchScale})")
            check(pr.rateScale in 0.9f..1.1f, "rate scale sane for ${pr.mood} (${pr.rateScale})")
            check(pr.gainDb in -3f..3f, "gain sane for ${pr.mood}")
        }

        val rnd = Random(SEED + 13)
        repeat(120) {
            val amp = rnd.nextFloat() * 0.9f
            val hz = 80.0 + rnd.nextDouble() * 300.0
            val p = ProsodyAnalyzer.analyze(tone(rnd.nextInt(400, 12000), sr, amp = amp, hz = hz), sr)
            check(p.rmsDb <= 0f, "rms never positive")
            check(p.pitchScale in 0.9f..1.1f && p.rateScale in 0.9f..1.1f, "random prosody bounded")
        }
    }


    group("WaveformPeaks") {
        val sig = FloatArray(16000) { i -> (0.5 * sin(2.0 * Math.PI * 4 * i / 16000.0)).toFloat() }
        val peaks = WaveformPeaks.peaks(sig, 64)
        check(peaks.size == 64, "bucket count")
        check(peaks.all { it in 0f..1f }, "normalised to 0..1")
        near(peaks.maxOrNull() ?: 0f, 1f, 1e-4f, "loudest bucket is 1.0")
        check(WaveformPeaks.peaks(FloatArray(0), 32).size == 32, "empty input still returns buckets")
        check(WaveformPeaks.peaks(sig, 0).isEmpty(), "zero buckets")
        val quiet = WaveformPeaks.peaks(FloatArray(1000) { 0.001f }, 16)
        check(quiet.all { it <= 1f }, "quiet signal bounded")

        val rnd = Random(SEED + 17)
        repeat(80) {
            val n = rnd.nextInt(1, 20000)
            val b = rnd.nextInt(1, 400)
            val data = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
            val out = WaveformPeaks.peaks(data, b)
            check(out.size == b, "random peaks size")
            check(out.all { it.isFinite() && it in 0f..1f }, "random peaks bounded")
        }
    }

    group("StorageManager") {
        check(StorageManager.human(0) == "0 B", "zero bytes")
        check(StorageManager.human(2048).contains("KB"), "kilobytes")
        check(StorageManager.human(5L * 1024 * 1024).contains("MB"), "megabytes")
        check(StorageManager.human(3L * 1024 * 1024 * 1024).contains("GB"), "gigabytes")

        val dir = tmpDir()
        val manager = StorageManager(dir)
        check(manager.outputs().isEmpty(), "empty folder")
        File(dir, "a.mp4").writeText("x".repeat(100))
        File(dir, "b.txt").writeText("ignore me")
        File(dir, "c.MP4").writeText("x".repeat(50))
        check(manager.outputs().size == 2, "only mp4 files listed")
        check(manager.totalBytes() == 150L, "total bytes")
        check(manager.delete(manager.outputs().first()), "delete returns true")
        check(manager.outputs().size == 1, "one left after delete")
        check(manager.deleteAll() == 1, "deleteAll count")
        check(manager.outputs().isEmpty(), "empty after deleteAll")
    }


    group("WavReader") {
        fun wav16(samples: FloatArray, channels: Int, rate: Int): ByteArray {
            val data = ByteArray(samples.size * 2)
            val sb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (v in samples) sb.put((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()); header.putInt(36 + data.size)
            header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray())
            header.putInt(16); header.putShort(1); header.putShort(channels.toShort())
            header.putInt(rate); header.putInt(rate * channels * 2)
            header.putShort((channels * 2).toShort()); header.putShort(16)
            header.put("data".toByteArray()); header.putInt(data.size)
            return header.array() + data
        }

        val samples = floatArrayOf(0.5f, -0.5f, 0.25f, -0.25f)
        val parsed = WavReader.parse(wav16(samples, 2, 24000))
        check(parsed != null, "stereo WAV parses")
        check(parsed!!.channels == 2, "channel count")
        check(parsed.sampleRate == 24000, "sample rate")
        near(parsed.samples[0], 0.5f, 1f / 16000f, "first sample")
        near(parsed.samples[1], -0.5f, 1f / 16000f, "second sample")

        val mono = WavReader.toMono(parsed)
        check(mono.size == 2, "downmix frame count")
        near(mono[0], 0f, 1f / 16000f, "stereo pair averages to zero")

        check(WavReader.parse(ByteArray(10)) == null, "too short")
        check(WavReader.parse(ByteArray(64)) == null, "not a RIFF file")
        check(WavReader.parse(wav16(FloatArray(0), 1, 16000))?.samples?.isEmpty() != false,
            "empty payload handled")
    }

    group("LoudnessNormalizer") {
        val quiet = FloatArray(8000) { 0.02f }
        val qp = LoudnessNormalizer.plan(quiet)
        check(qp.gain > 1f, "quiet clip is boosted (${qp.gain})")
        check(qp.measuredDb < -30f, "quiet measured low (${qp.measuredDb})")

        val loud = FloatArray(8000) { 0.95f }
        val lp = LoudnessNormalizer.plan(loud)
        check(lp.gain <= 1f, "loud clip is not boosted (${lp.gain})")
        check(loud.maxOf { kotlin.math.abs(it) } * lp.gain <= LoudnessNormalizer.PEAK_CEILING + 1e-3f,
            "peak stays under the ceiling")

        check(!LoudnessNormalizer.plan(FloatArray(1000)).applied, "silence is left alone")
        val rnd = Random(SEED + 19)
        repeat(60) {
            val data = FloatArray(rnd.nextInt(100, 8000)) { (rnd.nextFloat() - 0.5f) * rnd.nextFloat() * 2f }
            val p = LoudnessNormalizer.plan(data)
            check(p.gain in 0.25f..4f, "gain bounded (${p.gain})")
        }
    }

    group("EngineRouter") {
        val off = EngineRouter.plan(
            EngineRouter.Request(cloudEnabled = false, networkAvailable = true, configuredProviders = "openai")
        )
        check(!off.anyCloud, "cloud off → all local")

        val offline = EngineRouter.plan(
            EngineRouter.Request(cloudEnabled = true, networkAvailable = false, configuredProviders = "openai")
        )
        check(!offline.anyCloud, "no network → all local")

        val on = EngineRouter.plan(
            EngineRouter.Request(cloudEnabled = true, networkAvailable = true, configuredProviders = "openai")
        )
        check(on.mt.isCloud && on.mt.providerId == "openai", "MT goes cloud with a key")
        check(!on.asr.isCloud, "ASR stays local by default")
        check(!on.tts.isCloud, "TTS stays local unless chosen")

        val withTts = EngineRouter.plan(
            EngineRouter.Request(
                cloudEnabled = true, networkAvailable = true,
                configuredProviders = "openai,gemini", cloudTtsProvider = "openai"
            )
        )
        check(withTts.tts.isCloud && withTts.tts.providerId == "openai", "cloud voice honoured")

        val badTts = EngineRouter.plan(
            EngineRouter.Request(
                cloudEnabled = true, networkAvailable = true,
                configuredProviders = "openai", cloudTtsProvider = "anthropic"
            )
        )
        check(!badTts.tts.isCloud, "cloud voice ignored when that key is absent")

        val noKeys = EngineRouter.plan(
            EngineRouter.Request(cloudEnabled = true, networkAvailable = true, configuredProviders = "")
        )
        check(!noKeys.anyCloud, "no keys → all local")

        check(off.summary().contains("local"), "summary readable")
    }

    group("Diag") {
        Diag.clear()
        Diag.setClockForTest { 42L }
        repeat(500) { Diag.record("T", "event $it") }
        check(Diag.snapshot().size == 400, "ring buffer bounded")
        check(Diag.snapshot().last().message == "event 499", "newest retained")
        check(Diag.snapshot().first().atMs == 42L, "injected clock used")
        check(Diag.dump(5).lines().size == 5, "dump limit")
        Diag.clear()
        check(Diag.snapshot().isEmpty(), "clear")
    }


    group("PcmStore edge cases") {
        val dir = tmpDir()
        val st = PcmStore(File(dir, "edge.pcm"), 24000, 1)
        // writing past the current end must extend the file and leave a silent gap
        st.writeMono(1000, FloatArray(10) { 0.9f }, 10)
        check(st.frameCount() == 1010L, "write past end extends file (${st.frameCount()})")
        val gap = st.readFrames(0, 1000)
        check(gap.all { abs(it) < 1e-6f }, "gap before first write is silent")
        near(st.readFrames(1000, 1)[0], 0.9f, 4f / 32768f, "value after gap")

        // clipping input must be clamped, not wrapped around
        st.writeMono(0, floatArrayOf(2f, -2f), 2)
        val clipped = st.readFrames(0, 2)
        near(clipped[0], 1f, 1e-3f, "positive overflow clamped")
        near(clipped[1], -1f, 1e-3f, "negative overflow clamped")

        // zero-length write is a no-op
        st.writeMono(5, FloatArray(0), 0)
        check(st.frameCount() == 1010L, "zero-length write changes nothing")
        st.closeAndDelete()
    }

    group("AudioMixer stereo detail") {
        val dir = tmpDir()
        val bed = PcmStore(File(dir, "side.pcm"), 24000, 2).apply { ensureFrames(100) }
        // hard-panned content: L only. Suppression (L-R)/2 must keep half of it.
        val lr = FloatArray(200)
        for (i in 0 until 100) {
            lr[i * 2] = 0.6f
            lr[i * 2 + 1] = 0f
        }
        bed.writeFrames(0, lr, 100)
        val voice = PcmStore(File(dir, "v.pcm"), 24000, 1).apply { ensureFrames(100) }
        val out = PcmStore(File(dir, "o.pcm"), 24000, 1).apply { ensureFrames(100) }

        AudioMixer.streamMix(
            voice, bed, out, 100,
            AudioMixer.MixConfig(bedGainDb = 0f, suppressCenter = true, sourceIsMono = false)
        )
        near(out.readFrames(0, 1)[0], 0.3f, 4f / 32768f, "hard-panned side content kept at half")

        // very loud voice must soft-clip, never exceed 1.0
        voice.writeMono(0, FloatArray(100) { 0.95f }, 100)
        out.ensureFrames(100)
        AudioMixer.streamMix(
            voice, bed, out, 100,
            AudioMixer.MixConfig(bedGainDb = 0f, suppressCenter = false, sourceIsMono = false)
        )
        val loud = out.readFrames(0, 100)
        check(loud.all { abs(it) <= 1.0f }, "normalised mix never exceeds 1.0")
        check(loud.any { abs(it) > 0.9f }, "loud content still loud after normalisation")
        check(loud.maxOf { abs(it) } <= 0.985f, "peak lands on the 0.98 target")
        val g = AudioMixer.streamMix(
            voice, bed, out, 100,
            AudioMixer.MixConfig(bedGainDb = 0f, suppressCenter = false, sourceIsMono = false)
        )
        check(g < 1f, "gain reported below 1.0 when clipping was avoided ($g)")

        bed.closeAndDelete(); voice.closeAndDelete(); out.closeAndDelete()
    }

    group("JsonArrays robustness") {
        // unterminated input must terminate, not hang
        val partial = JsonArrays.parseStrings("[\"abc")
        check(partial.isEmpty(), "unterminated string returns nothing")
        check(JsonArrays.parseStrings("[{\"a\":1}, \"b\"]") == listOf("b"),
            "object before string skipped")
        check(JsonArrays.parseStrings("[\"a\",\"b\",]") == listOf("a", "b"), "trailing comma")
        check(JsonArrays.parseStrings("  [  \"a\"  ,  \"b\"  ]  ") == listOf("a", "b"), "whitespace")
        check(JsonArrays.parseStrings("[\"\"]") == listOf(""), "empty string element kept")
    }

    group("Diag concurrency") {
        Diag.clear()
        Diag.setClockForTest { 1L }
        val threads = (0 until 4).map {
            Thread {
                repeat(500) { i -> Diag.record("T$it", "e$i") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        check(Diag.snapshot().size == 400, "concurrent writes stay bounded")
    }

    group("Vad thresholds") {
        // a constant DC signal is not speech: zero crossings are irrelevant, energy is high
        val dc = FloatArray(8000) { 0.2f }
        val dcSpans = Vad.spans(dc, 16000)
        check(dcSpans.size <= 1, "steady DC treated as one region, not many")

        // one frame of audio must not crash
        check(Vad.spans(FloatArray(1), 16000).isEmpty(), "single sample input")

        // amplitude sweep: louder signals must never produce fewer spans
        var previous = -1
        for (amp in listOf(0.001f, 0.01f, 0.05f, 0.2f, 0.6f)) {
            val sig = concat(silence(4000), tone(8000, amp = amp), silence(4000))
            val n = Vad.spans(sig, 16000).size
            check(n >= previous, "span count monotonic in amplitude (amp=$amp)")
            previous = n
        }
    }

    println()
    println("checks: $checks, failures: $failures")
    if (failures > 0) {
        println("RESULT: FAILED")
        kotlin.system.exitProcess(1)
    }
    println("RESULT: OK")
}
