package ai.hindidub.engine

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Dubbing voice.
 *
 * Two things make it usable rather than robotic:
 *  * the **system offline voice** by default — already on the phone, 0 MB in the APK, no GPL;
 *  * [setProsody] before each utterance, so a whispered line is voiced quieter and slower and
 *    a shout comes out faster and louder. Pitch/rate are applied as multipliers on the chosen
 *    profile, and clamped, because system TTS breaks up beyond about ±12 %.
 */
interface TtsEngine : Closeable {
    val sampleRate: Int
    suspend fun init(langTag: String, profile: VoiceProfile): Boolean
    fun setProsody(pitchScale: Float, rateScale: Float)
    suspend fun synthesizeToPcm(text: String, scratchFile: File): FloatArray?
}

class SystemTtsProcessor(private val context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null
    private var basePitch = 1f
    private var baseRate = 1f

    override var sampleRate: Int = 24000
        private set

    override suspend fun init(langTag: String, profile: VoiceProfile): Boolean {
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            tts = TextToSpeech(context.applicationContext) { status ->
                cont.resume(status == TextToSpeech.SUCCESS)
            }
        }
        if (!ok) return false

        val locale = Locale.forLanguageTag(langTag)
        val engine = tts ?: return false
        val avail = engine.isLanguageAvailable(locale)
        if (avail < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "voice for $langTag not installed (avail=$avail)")
            return false
        }
        engine.language = locale
        basePitch = profile.pitch
        baseRate = profile.rate
        engine.setPitch(basePitch)
        engine.setSpeechRate(baseRate)
        Log.i(TAG, "TTS ready (${profile.label}, pitch=$basePitch, rate=$baseRate)")
        return true
    }

    override fun setProsody(pitchScale: Float, rateScale: Float) {
        val engine = tts ?: return
        engine.setPitch((basePitch * pitchScale).coerceIn(0.5f, 2f))
        engine.setSpeechRate((baseRate * rateScale).coerceIn(0.5f, 2f))
    }

    override suspend fun synthesizeToPcm(text: String, scratchFile: File): FloatArray? {
        val engine = tts ?: return null
        if (text.isBlank()) return null
        val out = File(scratchFile.parentFile, scratchFile.name)
        if (out.exists()) out.delete()

        return suspendCancellableCoroutine { cont ->
            val utteranceId = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    val pcm = AudioUtils.readWavPcm16(out)
                    if (cont.isActive) cont.resume(pcm)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onError(id: String?, errorCode: Int) {
                    Log.e(TAG, "TTS error $errorCode")
                    if (cont.isActive) cont.resume(null)
                }
            })
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val rc = engine.synthesizeToFile(text, params, out, utteranceId)
            if (rc != TextToSpeech.SUCCESS && cont.isActive) cont.resume(null)
        }
    }

    override fun close() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val TAG = "TTS"
    }
}
