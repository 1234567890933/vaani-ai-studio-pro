package ai.hindidub.cloud

import ai.hindidub.core.DubLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Claude (Anthropic Messages API).
 *
 * Wire format differs from the OpenAI-style providers in three ways worth knowing:
 *  * the key goes in `x-api-key`, not a bearer header;
 *  * `anthropic-version` is mandatory;
 *  * the reply is `content: [{type:"text", text:"..."}]`, not `choices[]`.
 *
 * It shares the prompt builder and the JSON-array parser with the other providers, so a reply
 * that is fenced or has prose around it still parses.
 */
class AnthropicProvider : TranslationProvider {

    override val id = "anthropic"
    override val label = "Anthropic Claude"
    override val endpoint = "https://api.anthropic.com/v1/messages"

    override suspend fun translate(
        texts: List<String>,
        src: DubLanguage,
        tgt: DubLanguage,
        apiKey: String
    ): List<String> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 2048)
            .put("system", "You translate dialogue for dubbing. Reply with a JSON array only.")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", Http.buildPrompt(texts, src, tgt))
                )
            )

        val raw = Http.postJson(
            endpoint, body,
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )
        val content = JSONObject(raw).getJSONArray("content")
        val text = (0 until content.length())
            .map { content.getJSONObject(it) }
            .firstOrNull { it.optString("type") == "text" }
            ?.optString("text")
            ?: error("no text block in Claude reply")
        Http.parseStringArray(text, texts.size)
    }

    private companion object {
        const val MODEL = "claude-3-5-haiku-latest"
    }
}
