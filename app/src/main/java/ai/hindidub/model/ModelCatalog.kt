package ai.hindidub.model

/**
 * Every model the app can use — 8 ASR options, 3 translation options and 9 Piper voices.
 *
 * Every byte count below was read from the file's own HTTP Content-Length, so progress,
 * storage pre-flight and quota maths are exact. The pool totals roughly 13 GB if you install
 * everything, deliberately split by stage so each can be sized independently:
 *
 *   ASR  8 options   32 MB → 3.1 GB
 *   MT   3 options  283 MB → 3.5 GB
 *   TTS  9 Piper voices (optional) or the free system voice (0 MB)
 *
 * Tiers by device RAM:  S <3 GB · M 3–5 GB · L 5–7 GB · XL 7 GB+
 * The tiny and small Whisper models ship inside the APK, so a fresh install can dub
 * immediately; everything else is downloaded on demand.
 */
data class ModelFile(
    val url: String,
    val path: String,
    val bytes: Long,
    val assetPath: String? = null
) {
    val isBundled: Boolean get() = assetPath != null
}

data class ModelGroup(
    val id: String,
    val label: String,
    val tier: String,
    val kind: Kind,
    val note: String,
    val license: String,
    val minRamMb: Int,
    val language: String,
    val files: List<ModelFile>
) {
    enum class Kind { ASR, MT, TTS }

    val totalBytes: Long get() = files.sumOf { it.bytes }
    val totalMb: Long get() = totalBytes / (1024 * 1024)
    val isBundledOnly: Boolean get() = files.all { it.isBundled }
}

object ModelCatalog {

    private const val WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    private const val NLLB = "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/"
    private const val IT2 =
        "https://huggingface.co/hari31416/indictrans2-en-indic-dist-200M-ONNX-int8/resolve/main/"
    private const val PIPER = "https://huggingface.co/rhasspy/piper-voices/resolve/main/"

    // ---- ASR -------------------------------------------------------------

    val asrTiny = ModelGroup(
        id = "asr-tiny",
        label = "Whisper tiny q5_1 (bundled)",
        tier = "S",
        kind = ModelGroup.Kind.ASR,
        note = "Ships inside the app — a first dub with no download at all.",
        license = "MIT",
        minRamMb = 1500,
        language = "multi",
        files = listOf(
            ModelFile(
                url = WHISPER + "ggml-tiny-q5_1.bin",
                path = "whisper/ggml-tiny-q5_1.bin",
                bytes = 32152673L,
                assetPath = "models/ggml-tiny-q5_1.bin"
            )
        )
    )

    val asrBase = ModelGroup(
        id = "asr-base",
        label = "Whisper base q5_1",
        tier = "S",
        kind = ModelGroup.Kind.ASR,
        note = "Smallest useful download; Hindi accuracy is moderate.",
        license = "MIT",
        minRamMb = 2000,
        language = "multi",
        files = listOf(
            ModelFile(
                url = WHISPER + "ggml-base-q5_1.bin",
                path = "whisper/ggml-base-q5_1.bin",
                bytes = 59707625L
                
            )
        )
    )

    val asrSmall = ModelGroup(
        id = "asr-small",
        label = "Whisper small q5_1 (bundled + downloadable)",
        tier = "M",
        kind = ModelGroup.Kind.ASR,
        note = "Best accuracy per second on a Dimensity 6000. Also shipped in the APK.",
        license = "MIT",
        minRamMb = 2500,
        language = "multi",
        files = listOf(
            ModelFile(
                url = WHISPER + "ggml-small-q5_1.bin",
                path = "whisper/ggml-small-q5_1.bin",
                bytes = 190085487L,
                assetPath = "models/ggml-small-q5_1.bin"
            )
        )
    )

    val asrMedium = ModelGroup(
        id = "asr-medium",
        label = "Whisper medium q5_0",
        tier = "L",
        kind = ModelGroup.Kind.ASR,
        note = "Noticeably better on accents and overlapping dialogue. Slow.",
        license = "MIT",
        minRamMb = 3500,
        language = "multi",
        files = listOf(
            ModelFile(
                url = WHISPER + "ggml-medium-q5_0.bin",
                path = "whisper/ggml-medium-q5_0.bin",
                bytes = 539212467L
                
            )
        )
    )

    val asrTurbo = ModelGroup(
        id = "asr-turbo",
        label = "Whisper large-v3-turbo q5_0",
        tier = "L",
        kind = ModelGroup.Kind.ASR,
        note = "Large-v3 accuracy with a cheaper decoder — best quality/time on 8 GB phones.",
        license = "MIT",
        minRamMb = 4000,
        language = "multi",
        files = listOf(
            ModelFile(
                url = WHISPER + "ggml-large-v3-turbo-q5_0.bin",
                path = "whisper/ggml-large-v3-turbo-q5_0.bin",
                bytes = 574041195L
                
            )
        )
    )

    val asrTurboFp16 = ModelGroup(
        id = "asr-turbo-fp16",
        label = "Whisper large-v3-turbo fp16",
        tier = "XL",
        kind = ModelGroup.Kind.ASR,
        note = "Unquantised turbo — for A/B checking what quantisation costs you.",
        license = "MIT",
        minRamMb = 6000,
        language = "multi",
        files = listOf(
            ModelFile(
                url = WHISPER + "ggml-large-v3-turbo.bin",
                path = "whisper/ggml-large-v3-turbo.bin",
                bytes = 1624555275L
                
            )
        )
    )

    val asrLarge = ModelGroup(
        id = "asr-large",
        label = "Whisper large-v3 q5_0",
        tier = "XL",
        kind = ModelGroup.Kind.ASR,
        note = "Best transcript this app can run. Expect several times real time.",
        license = "MIT",
        minRamMb = 6000,
        language = "multi",
        files = listOf(
            ModelFile(
                url = WHISPER + "ggml-large-v3-q5_0.bin",
                path = "whisper/ggml-large-v3-q5_0.bin",
                bytes = 1081140203L
                
            )
        )
    )

    val asrLargeFp16 = ModelGroup(
        id = "asr-large-fp16",
        label = "Whisper large-v3 fp16 (reference)",
        tier = "XL",
        kind = ModelGroup.Kind.ASR,
        note = "Unquantised reference weights — not for daily use.",
        license = "MIT",
        minRamMb = 9000,
        language = "multi",
        files = listOf(
            ModelFile(
                url = WHISPER + "ggml-large-v3.bin",
                path = "whisper/ggml-large-v3.bin",
                bytes = 3095033483L
                
            )
        )
    )

    // ---- MT --------------------------------------------------------------

    val mtIndicTrans2 = ModelGroup(
        id = "mt-indictrans2",
        label = "IndicTrans2 distilled 200M (INT8)",
        tier = "M",
        kind = ModelGroup.Kind.MT,
        note = "MIT licence, smallest MT option. Needs the Unigram tokenizer work in ARCHITECTURE §6.",
        license = "MIT",
        minRamMb = 2500,
        language = "indic",
        files = listOf(
            ModelFile(IT2 + "encoder_model.onnx", "mt2/encoder_model.onnx", 831_399L),
            ModelFile(IT2 + "encoder_model.onnx.data", "mt2/encoder_model.onnx.data", 73_791_488L),
            ModelFile(IT2 + "decoder_model.onnx", "mt2/decoder_model.onnx", 2_037_502L),
            ModelFile(IT2 + "decoder_shared.onnx.data", "mt2/decoder_shared.onnx.data", 203_048_944L),
            ModelFile(IT2 + "model.SRC", "mt2/model.SRC", 759_425L),
            ModelFile(IT2 + "model.TGT", "mt2/model.TGT", 3_256_903L)
        )
    )

    val mtNllb = ModelGroup(
        id = "mt-nllb",
        label = "NLLB-200 distilled 600M (INT8)",
        tier = "M",
        kind = ModelGroup.Kind.MT,
        note = "50 source languages → Indian languages. Needs ~1.6 GB free RAM while decoding.",
        license = "CC-BY-NC-4.0",
        minRamMb = 3000,
        language = "multi",
        files = listOf(
            ModelFile(NLLB + "onnx/encoder_model_quantized.onnx", "mt/encoder.onnx", 419_120_483L),
            ModelFile(NLLB + "onnx/decoder_model_quantized.onnx", "mt/decoder.onnx", 470_533_055L),
            ModelFile(NLLB + "sentencepiece.bpe.model", "mt/sentencepiece.bpe.model", 4_852_054L),
            ModelFile(NLLB + "tokenizer.json", "mt/tokenizer.json", 17_331_224L)
        )
    )

    val mtNllbFp32 = ModelGroup(
        id = "mt-nllb-fp32",
        label = "NLLB-200 distilled 600M (fp32 reference)",
        tier = "XL",
        kind = ModelGroup.Kind.MT,
        note = "Unquantised. Use it to measure what INT8 costs you in translation quality.",
        license = "CC-BY-NC-4.0",
        minRamMb = 7000,
        language = "multi",
        files = listOf(
            ModelFile(NLLB + "onnx/encoder_model.onnx", "mtf/encoder.onnx", 1_658_468_942L),
            ModelFile(NLLB + "onnx/decoder_model.onnx", "mtf/decoder.onnx", 1_860_454_885L),
            ModelFile(NLLB + "sentencepiece.bpe.model", "mtf/sentencepiece.bpe.model", 4_852_054L),
            ModelFile(NLLB + "tokenizer.json", "mtf/tokenizer.json", 17_331_224L)
        )
    )

    // ---- TTS (optional neural voices) ------------------------------------
    // Piper voices are MIT, but their phonemiser (espeak-ng) is GPL-3.0: shipping them makes
    // the app GPL. They are offered as an optional download, not bundled. The default path
    // stays the free system voice, which is already offline on most phones.

    val ttsHiInPrathamMedium = ModelGroup(
        id = "tts-hi_IN-pratham-medium",
        label = "Hindi · male (hi_IN-pratham-medium)",
        tier = "L",
        kind = ModelGroup.Kind.TTS,
        note = "Neural voice, ~64 MB. Needs espeak-ng phonemisation (GPL-3.0).",
        license = "MIT (voice) / GPL-3.0 (espeak-ng)",
        minRamMb = 2500,
        language = "hi",
        files = listOf(
            ModelFile(PIPER + "hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx", "tts/hi_IN-pratham-medium.onnx", 63516050L),
            ModelFile(PIPER + "hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx.json", "tts/hi_IN-pratham-medium.onnx.json", 4970L)
        )
    )

    val ttsHiInPriyamvadaMedium = ModelGroup(
        id = "tts-hi_IN-priyamvada-medium",
        label = "Hindi · female (hi_IN-priyamvada-medium)",
        tier = "L",
        kind = ModelGroup.Kind.TTS,
        note = "Neural voice, ~64 MB. Needs espeak-ng phonemisation (GPL-3.0).",
        license = "MIT (voice) / GPL-3.0 (espeak-ng)",
        minRamMb = 2500,
        language = "hi",
        files = listOf(
            ModelFile(PIPER + "hi/hi_IN/priyamvada/medium/hi_IN-priyamvada-medium.onnx", "tts/hi_IN-priyamvada-medium.onnx", 63516050L),
            ModelFile(PIPER + "hi/hi_IN/priyamvada/medium/hi_IN-priyamvada-medium.onnx.json", "tts/hi_IN-priyamvada-medium.onnx.json", 4973L)
        )
    )

    val ttsHiInRohanMedium = ModelGroup(
        id = "tts-hi_IN-rohan-medium",
        label = "Hindi · male, deeper (hi_IN-rohan-medium)",
        tier = "L",
        kind = ModelGroup.Kind.TTS,
        note = "Neural voice, ~63 MB. Needs espeak-ng phonemisation (GPL-3.0).",
        license = "MIT (voice) / GPL-3.0 (espeak-ng)",
        minRamMb = 2500,
        language = "hi",
        files = listOf(
            ModelFile(PIPER + "hi/hi_IN/rohan/medium/hi_IN-rohan-medium.onnx", "tts/hi_IN-rohan-medium.onnx", 62950044L),
            ModelFile(PIPER + "hi/hi_IN/rohan/medium/hi_IN-rohan-medium.onnx.json", "tts/hi_IN-rohan-medium.onnx.json", 5041L)
        )
    )

    val ttsTeInMayaMedium = ModelGroup(
        id = "tts-te_IN-maya-medium",
        label = "Telugu · female (te_IN-maya-medium)",
        tier = "L",
        kind = ModelGroup.Kind.TTS,
        note = "Neural voice, ~63 MB. Needs espeak-ng phonemisation (GPL-3.0).",
        license = "MIT (voice) / GPL-3.0 (espeak-ng)",
        minRamMb = 2500,
        language = "te",
        files = listOf(
            ModelFile(PIPER + "te/te_IN/maya/medium/te_IN-maya-medium.onnx", "tts/te_IN-maya-medium.onnx", 62950044L),
            ModelFile(PIPER + "te/te_IN/maya/medium/te_IN-maya-medium.onnx.json", "tts/te_IN-maya-medium.onnx.json", 5040L)
        )
    )

    val ttsTeInPadmavathiMedium = ModelGroup(
        id = "tts-te_IN-padmavathi-medium",
        label = "Telugu · female, brighter (te_IN-padmavathi-medium)",
        tier = "L",
        kind = ModelGroup.Kind.TTS,
        note = "Neural voice, ~64 MB. Needs espeak-ng phonemisation (GPL-3.0).",
        license = "MIT (voice) / GPL-3.0 (espeak-ng)",
        minRamMb = 2500,
        language = "te",
        files = listOf(
            ModelFile(PIPER + "te/te_IN/padmavathi/medium/te_IN-padmavathi-medium.onnx", "tts/te_IN-padmavathi-medium.onnx", 63516050L),
            ModelFile(PIPER + "te/te_IN/padmavathi/medium/te_IN-padmavathi-medium.onnx.json", "tts/te_IN-padmavathi-medium.onnx.json", 4974L)
        )
    )

    val ttsMlInArjunMedium = ModelGroup(
        id = "tts-ml_IN-arjun-medium",
        label = "Malayalam · male (ml_IN-arjun-medium)",
        tier = "L",
        kind = ModelGroup.Kind.TTS,
        note = "Neural voice, ~63 MB. Needs espeak-ng phonemisation (GPL-3.0).",
        license = "MIT (voice) / GPL-3.0 (espeak-ng)",
        minRamMb = 2500,
        language = "ml",
        files = listOf(
            ModelFile(PIPER + "ml/ml_IN/arjun/medium/ml_IN-arjun-medium.onnx", "tts/ml_IN-arjun-medium.onnx", 62950044L),
            ModelFile(PIPER + "ml/ml_IN/arjun/medium/ml_IN-arjun-medium.onnx.json", "tts/ml_IN-arjun-medium.onnx.json", 5044L)
        )
    )

    val ttsMlInMeeraMedium = ModelGroup(
        id = "tts-ml_IN-meera-medium",
        label = "Malayalam · female (ml_IN-meera-medium)",
        tier = "L",
        kind = ModelGroup.Kind.TTS,
        note = "Neural voice, ~63 MB. Needs espeak-ng phonemisation (GPL-3.0).",
        license = "MIT (voice) / GPL-3.0 (espeak-ng)",
        minRamMb = 2500,
        language = "ml",
        files = listOf(
            ModelFile(PIPER + "ml/ml_IN/meera/medium/ml_IN-meera-medium.onnx", "tts/ml_IN-meera-medium.onnx", 62950044L),
            ModelFile(PIPER + "ml/ml_IN/meera/medium/ml_IN-meera-medium.onnx.json", "tts/ml_IN-meera-medium.onnx.json", 5045L)
        )
    )

    val ttsMrInGoogleMedium = ModelGroup(
        id = "tts-mr_IN-google-medium",
        label = "Marathi · neutral (mr_IN-google-medium)",
        tier = "L",
        kind = ModelGroup.Kind.TTS,
        note = "Neural voice, ~77 MB. Needs espeak-ng phonemisation (GPL-3.0).",
        license = "MIT (voice) / GPL-3.0 (espeak-ng)",
        minRamMb = 2500,
        language = "mr",
        files = listOf(
            ModelFile(PIPER + "mr/mr_IN/google/medium/mr_IN-google-medium.onnx", "tts/mr_IN-google-medium.onnx", 76768179L),
            ModelFile(PIPER + "mr/mr_IN/google/medium/mr_IN-google-medium.onnx.json", "tts/mr_IN-google-medium.onnx.json", 5467L)
        )
    )

    val ttsBnBdGoogleMedium = ModelGroup(
        id = "tts-bn_BD-google-medium",
        label = "Bengali · neutral (bn_BD-google-medium)",
        tier = "L",
        kind = ModelGroup.Kind.TTS,
        note = "Neural voice, ~77 MB. Needs espeak-ng phonemisation (GPL-3.0).",
        license = "MIT (voice) / GPL-3.0 (espeak-ng)",
        minRamMb = 2500,
        language = "bn",
        files = listOf(
            ModelFile(PIPER + "bn/bn_BD/google/medium/bn_BD-google-medium.onnx", "tts/bn_BD-google-medium.onnx", 76782515L),
            ModelFile(PIPER + "bn/bn_BD/google/medium/bn_BD-google-medium.onnx.json", "tts/bn_BD-google-medium.onnx.json", 5494L)
        )
    )

    val asrGroups = listOf(asrTiny, asrBase, asrSmall, asrMedium, asrTurbo, asrTurboFp16, asrLarge, asrLargeFp16)
    val mtGroups = listOf(mtIndicTrans2, mtNllb, mtNllbFp32)
    val ttsGroups = listOf(ttsHiInPrathamMedium, ttsHiInPriyamvadaMedium, ttsHiInRohanMedium, ttsTeInMayaMedium, ttsTeInPadmavathiMedium, ttsMlInArjunMedium, ttsMlInMeeraMedium, ttsMrInGoogleMedium, ttsBnBdGoogleMedium)
    val all = asrGroups + mtGroups + ttsGroups

    fun byId(id: String): ModelGroup? = all.firstOrNull { it.id == id }

    fun asrFor(ramMb: Long): ModelGroup = when {
        ramMb < 3000 -> asrBase
        ramMb < 5000 -> asrSmall
        ramMb < 7000 -> asrTurbo
        else -> asrLarge
    }

    fun mtFor(ramMb: Long): ModelGroup = if (ramMb >= 7000) mtNllbFp32 else mtNllb

    fun ttsFor(langCode: String): List<ModelGroup> = ttsGroups.filter { it.language == langCode }

    fun poolBytes(): Long = all.sumOf { it.totalBytes }
}
