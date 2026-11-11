package ai.hindidub.core

/**
 * Source and target languages.
 *
 * Rule of the app: **you can dub FROM anything, INTO Indian languages only.**
 *  * [sources] — every language Whisper can transcribe and NLLB can translate. Japanese,
 *    Korean, Chinese, English, Spanish … all work, because the pipeline never assumes the
 *    source is English. This is what makes anime dubbing possible: jpn_Jpan → hin_Deva
 *    directly, no English hop (a pivot through English is what makes dubs sound wrong).
 *  * [targets] — the 22 scheduled Indian languages + Bhojpuri.
 *
 * Each entry carries three codes and all three are used:
 *  * [whisper] — ISO code for whisper.cpp ("" = let Whisper auto-detect);
 *  * [nllb]    — FLORES-200 tag; it is an added token inside tokenizer.json, so
 *                TranslationEngine resolves it at runtime and fails loudly if missing;
 *  * [tts]     — BCP-47 locale Android TextToSpeech needs for the output voice.
 */
data class DubLanguage(
    val code: String,
    val englishName: String,
    val nativeName: String,
    val nllb: String,
    val tts: String,
    val script: String,
    val whisper: String = code,
    val isIndic: Boolean = false
)

object LanguageCatalog {

    // ---- targets: Indian languages only -----------------------------------

    val targets: List<DubLanguage> = listOf(
        DubLanguage("hi", "Hindi", "हिन्दी", "hin_Deva", "hi-IN", "Devanagari", "hi", true),
        DubLanguage("bn", "Bengali", "বাংলা", "ben_Beng", "bn-IN", "Bengali", "bn", true),
        DubLanguage("mr", "Marathi", "मराठी", "mar_Deva", "mr-IN", "Devanagari", "mr", true),
        DubLanguage("gu", "Gujarati", "ગુજરાતી", "guj_Gujr", "gu-IN", "Gujarati", "gu", true),
        DubLanguage("ta", "Tamil", "தமிழ்", "tam_Taml", "ta-IN", "Tamil", "ta", true),
        DubLanguage("te", "Telugu", "తెలుగు", "tel_Telu", "te-IN", "Telugu", "te", true),
        DubLanguage("kn", "Kannada", "ಕನ್ನಡ", "kan_Knda", "kn-IN", "Kannada", "kn", true),
        DubLanguage("ml", "Malayalam", "മലയാളം", "mal_Mlym", "ml-IN", "Malayalam", "ml", true),
        DubLanguage("pa", "Punjabi", "ਪੰਜਾਬੀ", "pan_Guru", "pa-IN", "Gurmukhi", "pa", true),
        DubLanguage("or", "Odia", "ଓଡ଼ିଆ", "ory_Orya", "or-IN", "Odia", "or", true),
        DubLanguage("as", "Assamese", "অসমীয়া", "asm_Beng", "as-IN", "Bengali", "as", true),
        DubLanguage("ur", "Urdu", "اردو", "urd_Arab", "ur-IN", "Arabic", "ur", true),
        DubLanguage("ne", "Nepali", "नेपाली", "npi_Deva", "ne-NP", "Devanagari", "ne", true),
        DubLanguage("sa", "Sanskrit", "संस्कृतम्", "san_Deva", "sa-IN", "Devanagari", "sa", true),
        DubLanguage("mai", "Maithili", "मैथिली", "mai_Deva", "hi-IN", "Devanagari", "mai", true),
        DubLanguage("bho", "Bhojpuri", "भोजपुरी", "bho_Deva", "hi-IN", "Devanagari", "", true),
        DubLanguage("doi", "Dogri", "डोगरी", "doi_Deva", "hi-IN", "Devanagari", "", true),
        DubLanguage("kok", "Konkani", "कोंकणी", "kok_Deva", "mr-IN", "Devanagari", "", true),
        DubLanguage("mni", "Manipuri", "ꯃꯤꯇꯩ ꯂꯣꯟ", "mni_Beng", "mni-IN", "Bengali", "", true),
        DubLanguage("brx", "Bodo", "बड़ो", "brx_Deva", "brx-IN", "Devanagari", "", true),
        DubLanguage("sat", "Santali", "ᱥᱟᱱᱛᱟᱲᱤ", "sat_Olck", "sat-IN", "Ol Chiki", "", true),
        DubLanguage("ks", "Kashmiri", "کٲشُر", "kas_Arab", "ks-IN", "Arabic", "ks", true),
        DubLanguage("sd", "Sindhi", "سنڌي", "snd_Arab", "sd-IN", "Arabic", "sd", true)
    )

    // ---- sources: anything Whisper + NLLB handle --------------------------

    /** Indian languages are also valid sources (Tamil → Hindi dubbing works). */
    private val indianSources = targets

    private val worldSources: List<DubLanguage> = listOf(
        DubLanguage("ja", "Japanese", "日本語", "jpn_Jpan", "ja-JP", "Japanese", "ja"),
        DubLanguage("ko", "Korean", "한국어", "kor_Hang", "ko-KR", "Hangul", "ko"),
        DubLanguage("zh", "Chinese", "中文", "zho_Hans", "zh-CN", "Han", "zh"),
        DubLanguage("en", "English", "English", "eng_Latn", "en-IN", "Latin", "en"),
        DubLanguage("es", "Spanish", "Español", "spa_Latn", "es-ES", "Latin", "es"),
        DubLanguage("fr", "French", "Français", "fra_Latn", "fr-FR", "Latin", "fr"),
        DubLanguage("de", "German", "Deutsch", "deu_Latn", "de-DE", "Latin", "de"),
        DubLanguage("pt", "Portuguese", "Português", "por_Latn", "pt-BR", "Latin", "pt"),
        DubLanguage("it", "Italian", "Italiano", "ita_Latn", "it-IT", "Latin", "it"),
        DubLanguage("ru", "Russian", "Русский", "rus_Cyrl", "ru-RU", "Cyrillic", "ru"),
        DubLanguage("uk", "Ukrainian", "Українська", "ukr_Cyrl", "uk-UA", "Cyrillic", "uk"),
        DubLanguage("ar", "Arabic", "العربية", "arb_Arab", "ar-SA", "Arabic", "ar"),
        DubLanguage("tr", "Turkish", "Türkçe", "tur_Latn", "tr-TR", "Latin", "tr"),
        DubLanguage("th", "Thai", "ไทย", "tha_Thai", "th-TH", "Thai", "th"),
        DubLanguage("vi", "Vietnamese", "Tiếng Việt", "vie_Latn", "vi-VN", "Latin", "vi"),
        DubLanguage("id", "Indonesian", "Bahasa Indonesia", "ind_Latn", "id-ID", "Latin", "id"),
        DubLanguage("ms", "Malay", "Bahasa Melayu", "zsm_Latn", "ms-MY", "Latin", "ms"),
        DubLanguage("tl", "Filipino", "Filipino", "tgl_Latn", "fil-PH", "Latin", "tl"),
        DubLanguage("fa", "Persian", "فارسی", "pes_Arab", "fa-IR", "Arabic", "fa"),
        DubLanguage("he", "Hebrew", "עברית", "heb_Hebr", "he-IL", "Hebrew", "he"),
        DubLanguage("pl", "Polish", "Polski", "pol_Latn", "pl-PL", "Latin", "pl"),
        DubLanguage("nl", "Dutch", "Nederlands", "nld_Latn", "nl-NL", "Latin", "nl"),
        DubLanguage("sv", "Swedish", "Svenska", "swe_Latn", "sv-SE", "Latin", "sv"),
        DubLanguage("cs", "Czech", "Čeština", "ces_Latn", "cs-CZ", "Latin", "cs"),
        DubLanguage("el", "Greek", "Ελληνικά", "ell_Grek", "el-GR", "Greek", "el"),
        DubLanguage("sw", "Swahili", "Kiswahili", "swh_Latn", "sw-KE", "Latin", "sw"),
        DubLanguage("am", "Amharic", "አማርኛ", "amh_Ethi", "am-ET", "Ethiopic", "am")
    )

    val sources: List<DubLanguage> = worldSources + indianSources

    fun byCode(code: String): DubLanguage? =
        sources.firstOrNull { it.code == code }

    fun targetByCode(code: String): DubLanguage? =
        targets.firstOrNull { it.code == code }

    /** Codes for whisper.cpp; empty string means "let Whisper detect". */
    fun whisperCode(code: String): String {
        val lang = byCode(code) ?: return "auto"
        return if (lang.whisper.isBlank()) "auto" else lang.whisper
    }

    /** Languages that reuse another language's TTS voice (Maithili → Hindi voice, etc.). */
    fun sharesVoiceWith(lang: DubLanguage): DubLanguage? =
        targets.firstOrNull { it.tts == lang.tts && it.code != lang.code }

    fun popular(): List<DubLanguage> = listOfNotNull(
        byCode("ja"), byCode("ko"), byCode("zh"), byCode("en"),
        byCode("es"), byCode("ru"), byCode("ar"), byCode("hi")
    )
}
