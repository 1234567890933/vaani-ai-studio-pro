package ai.hindidub.cloud

import ai.hindidub.core.DubLanguage
import ai.hindidub.core.WavReader
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Optional cloud voice.
 *
 * OpenAI's `/v1/audio/speech` can return **WAV**, which matters: it means the app gets raw PCM
 * with no decoder, no MediaCodec, no container parsing — [WavReader] turns it into floats and the
 * rest of the pipeline cannot tell it apart from the on-device voice.
 *
 * Google Cloud TTS is deliberately *not* here: it authenticates with an OAuth2 service account,
 * not an API key, and asking users to paste a service-account JSON into a phone app is a worse
 * idea than the feature is worth. The Gemini key covers translation; the voice stays local or
 * goes through OpenAI.
 */
object CloudTts {

    data class Clip(val pcm: FloatArray, val sampleRate: Int)

    suspend fun synthesize(
        text: String,
        apiKey: String,
        voice: String = "alloy",
        lang: DubLanguage? = null
    ): Clip? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        val body = JSONObject()
            .put("model", "gpt-4o-mini-tts")
            .put("voice", voice)
            .put("input", text)
            .put("response_format", "wav")

        val bytes = Http.postBytes(
            "https://api.openai.com/v1/audio/speech",
            body,
            mapOf("Authorization" to "Bearer $apiKey")
        )
        val audio = WavReader.parse(bytes)
        if (audio == null) {
            Log.w("CloudTts", "reply was not a WAV (${bytes.size} bytes)")
            return@withContext null
        }
        Clip(WavReader.toMono(audio), audio.sampleRate)
    }
}
