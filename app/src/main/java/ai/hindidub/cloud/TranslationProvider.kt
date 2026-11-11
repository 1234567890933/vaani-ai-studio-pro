package ai.hindidub.cloud

import ai.hindidub.core.DubLanguage
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional online translation.
 *
 * The app works fully offline; this layer exists because a cloud model translates idiom and
 * slang better than a 600 M on-device model, and because the user may already pay for one of
 * these services. Every provider is a plain HTTPS call — no SDK, no dependency, ~40 lines
 * each — and the router below spreads work across whichever keys are configured.
 *
 * Nothing is sent anywhere unless the user adds a key AND picks a cloud mode in Settings.
 */
interface TranslationProvider {
    val id: String
    val label: String
    /** Endpoint actually called, shown in Settings so the user can see where text goes. */
    val endpoint: String

    suspend fun translate(
        texts: List<String>,
        src: DubLanguage,
        tgt: DubLanguage,
        apiKey: String
    ): List<String>
}

/** Shared HTTP + parsing helpers so each provider stays tiny. */
internal object Http {

    fun postJson(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        timeoutMs: Int = 45_000
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            if (code !in 200..299) error("HTTP $code: ${text.take(240)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** POST that expects binary back (the speech endpoint returns WAV, not JSON). */
    fun postBytes(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        timeoutMs: Int = 90_000
    ): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                error("HTTP $code: ${err.take(240)}")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Asks for a JSON array of strings and tolerates fences/extra prose around it. */
    fun parseStringArray(raw: String, expected: Int): List<String> {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").substringBeforeLast("```").trim()
        }
        val start = s.indexOf('[')
        val end = s.lastIndexOf(']')
        if (start >= 0 && end > start) {
            val arr = JSONArray(s.substring(start, end + 1))
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            if (out.size == expected) return out
        }
        // Fallback: one line per segment
        val lines = s.lines().map { it.trim().trim('"', ',') }.filter { it.isNotEmpty() }
        if (lines.size == expected) return lines
        error("provider returned ${lines.size} lines, expected $expected")
    }

    fun buildPrompt(texts: List<String>, src: DubLanguage, tgt: DubLanguage): String {
        val rules = listOf(
            "You are a professional dubbing translator into " + tgt.englishName + " (" + tgt.script + ").",
            "Translate each numbered line from " + src.englishName + " into " + tgt.englishName + ".",
            "Rules:",
            "- Keep the speaker's register, slang, idioms and emotional intensity.",
            "- Do not soften, censor or add explanations.",
            "- Keep names, numbers and on-screen text unchanged.",
            "- Return ONLY a JSON array of " + texts.size + " strings, same order, no commentary."
        ).joinToString("\n")
        val lines = texts.mapIndexed { i, t -> (i + 1).toString() + ". " + t }.joinToString("\n")
        return rules + "\n\n" + lines
    }
}

class SarvamProvider : TranslationProvider {
    override val id = "sarvam"
    override val label = "Sarvam AI"
    override val endpoint = "https://api.sarvam.ai/translate"

    override suspend fun translate(
        texts: List<String>,
        src: DubLanguage,
        tgt: DubLanguage,
        apiKey: String
    ): List<String> = withContext(Dispatchers.IO) {
        texts.map { text ->
            val body = JSONObject()
                .put("input", text)
                .put("source_language_code", if (src.code == "en") "en-IN" else src.tts)
                .put("target_language_code", tgt.tts)
                .put("enable_preprocessing", true)
            val raw = Http.postJson(
                endpoint, body, mapOf("api-subscription-key" to apiKey)
            )
            JSONObject(raw).optString("translated_text").ifBlank { text }
        }
    }
}

class GeminiProvider : TranslationProvider {
    override val id = "gemini"
    override val label = "Google Gemini"
    override val endpoint =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

    override suspend fun translate(
        texts: List<String>,
        src: DubLanguage,
        tgt: DubLanguage,
        apiKey: String
    ): List<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put(
            "contents", JSONArray().put(
                JSONObject().put(
                    "parts", JSONArray().put(
                        JSONObject().put("text", Http.buildPrompt(texts, src, tgt))
                    )
                )
            )
        )
        val raw = Http.postJson("$endpoint?key=$apiKey", body, emptyMap())
        val text = JSONObject(raw)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text")
        Http.parseStringArray(text, texts.size)
    }
}

/** OpenAI and xAI share the chat-completions wire format, so one class serves both. */
class ChatCompletionsProvider(
    override val id: String,
    override val label: String,
    override val endpoint: String,
    private val model: String
) : TranslationProvider {

    override suspend fun translate(
        texts: List<String>,
        src: DubLanguage,
        tgt: DubLanguage,
        apiKey: String
    ): List<String> = withContext(Dispatchers.IO) {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You translate dialogue for dubbing. Output JSON only."))
            .put(JSONObject().put("role", "user").put("content", Http.buildPrompt(texts, src, tgt)))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.3)
        val raw = Http.postJson(
            endpoint, body, mapOf("Authorization" to "Bearer $apiKey")
        )
        val text = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        Http.parseStringArray(text, texts.size)
    }
}

object Providers {
    val openAi = ChatCompletionsProvider(
        "openai", "OpenAI ChatGPT", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"
    )
    val grok = ChatCompletionsProvider(
        "grok", "xAI Grok", "https://api.x.ai/v1/chat/completions", "grok-2-latest"
    )

    val anthropic = AnthropicProvider()

    val all: List<TranslationProvider> =
        listOf(GeminiProvider(), openAi, anthropic, grok, SarvamProvider())

    fun byId(id: String): TranslationProvider? = all.firstOrNull { it.id == id }
}
