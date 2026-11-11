package ai.hindidub.core

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Settings store.
 *
 * Writes are debounced and atomic: dragging a slider marks the object dirty, [flush] writes
 * once. The file is replaced via temp + rename, so a kill mid-write cannot corrupt it.
 */
class AppPrefs(context: Context) {

    private val file = File(context.filesDir, "settings.json")
    private val json: JSONObject = runCatching {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    }.getOrElse { JSONObject() }

    private var dirty = false

    var sourceLang: String
        get() = json.optString("sourceLang", "en")
        set(v) = put("sourceLang", v)

    var targetLang: String
        get() = json.optString("targetLang", "hi")
        set(v) = put("targetLang", v)

    var voiceProfileId: String
        get() = json.optString("voiceProfileId", "natural")
        set(v) = put("voiceProfileId", v)

    var asrGroupId: String
        get() = json.optString("asrGroupId", "asr-medium")
        set(v) = put("asrGroupId", v)

    var mtGroupId: String
        get() = json.optString("mtGroupId", "mt-nllb")
        set(v) = put("mtGroupId", v)

    var bedGainDb: Float
        get() = json.optDouble("bedGainDb", -14.0).toFloat()
        set(v) = put("bedGainDb", v.toDouble())

    var suppressCenter: Boolean
        get() = json.optBoolean("suppressCenter", true)
        set(v) = put("suppressCenter", v)

    var threads: Int
        get() = json.optInt("threads", 0)
        set(v) = put("threads", v)

    var quotaGb: Int
        get() = json.optInt("quotaGb", 8)
        set(v) = put("quotaGb", v)

    var setupDone: Boolean
        get() = json.optBoolean("setupDone", false)
        set(v) = put("setupDone", v)

    var translationMode: String
        get() = json.optString("translationMode", "offline")
        set(v) = put("translationMode", v)

    var providerOrder: String
        get() = json.optString("providerOrder", "")
        set(v) = put("providerOrder", v)

    var routingMode: String
        get() = json.optString("routingMode", "parallel")
        set(v) = put("routingMode", v)

    var quickSeconds: Int
        get() = json.optInt("quickSeconds", 0)
        set(v) = put("quickSeconds", v)

    /** Output sample rate for the dubbed track: 24000, 32000 or 44100. */
    var mixSampleRate: Int
        get() = json.optInt("mixSampleRate", 24000)
        set(v) = put("mixSampleRate", v)

    /** AAC bitrate for the dubbed track, kbps: 64, 96 or 128. */
    var aacKbps: Int
        get() = json.optInt("aacKbps", 96)
        set(v) = put("aacKbps", v)

    /** Master switch for the hybrid (cloud) path. */
    var cloudEnabled: Boolean
        get() = json.optBoolean("cloudEnabled", false)
        set(v) = put("cloudEnabled", v)

    /** "" = on-device voice, "openai" = cloud voice. */
    var cloudTtsProvider: String
        get() = json.optString("cloudTtsProvider", "")
        set(v) = put("cloudTtsProvider", v)

    /** system | light | dark */
    var themeMode: String
        get() = json.optString("themeMode", "system")
        set(v) = put("themeMode", v)

    private fun put(key: String, value: Any) {
        if (json.opt(key) == value) return
        json.put(key, value)
        dirty = true
    }

    fun flush() {
        if (!dirty) return
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            tmp.writeText(json.toString(2))
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) error("rename failed")
            dirty = false
        }.onFailure { Log.w("AppPrefs", "save failed: ${it.message}") }
    }
}

/**
 * AES-256-GCM secrets in the Android Keystore. The key never leaves the Keystore; only
 * ciphertext is written, so a backup or a rooted file dump does not expose API keys.
 */
object SecretStore {

    private const val TAG = "SecretStore"
    private const val KEY_ALIAS = "hindidub.secrets.v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun file(context: Context) = File(context.filesDir, "secrets.bin")

    fun put(context: Context, name: String, value: String) {
        if (value.isBlank()) {
            remove(context, name)
            return
        }
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(value.toByteArray())
            val blob = JSONObject()
                .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            val store = JSONObject(
                runCatching { file(context).readText() }.getOrElse { "{}" }
            ).put(name, blob.toString())
            file(context).writeText(store.toString())
        }.onFailure { Log.w(TAG, "write failed: ${it.message}") }
    }

    fun get(context: Context, name: String): String? = runCatching {
        val store = JSONObject(file(context).readText())
        val blob = JSONObject(store.getString(name))
        val iv = Base64.decode(blob.getString("iv"), Base64.NO_WRAP)
        val ct = Base64.decode(blob.getString("ct"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct))
    }.onFailure { Log.w(TAG, "read failed: ${it.message}") }.getOrNull()

    fun has(context: Context, name: String): Boolean = !get(context, name).isNullOrBlank()

    fun remove(context: Context, name: String) {
        runCatching {
            val store = JSONObject(file(context).readText()).apply { remove(name) }
            file(context).writeText(store.toString())
        }
    }
}
