package ai.hindidub.engine

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Tone / slang post-editing for the translated text.
 *
 * Why this exists: a neural translation model (NLLB) has no chat alignment layer, so it
 * does not refuse or soften anything — it translates what it is given, including slang and
 * harsh dialogue. What NMT *does* struggle with is register: idioms come out literal and
 * flat. This glossary is a plain, user-editable find/replace layer applied after
 * translation so you can pin your own style choices ("bro" → "भाई", a phrase you want
 * phrased your way, and so on).
 *
 * File: <app files>/style_glossary.json
 * Format: { "source phrase": "replacement", ... }   (case-insensitive match)
 *
 * The shipped file is intentionally empty: what goes in it is your editorial choice for
 * your own content, and the app does not presume it.
 */
class StyleGlossary(private val file: File) {

    private val map = LinkedHashMap<String, String>()

    fun load(): Int {
        map.clear()
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("{}\n")
            return 0
        }
        return try {
            val root = JSONObject(file.readText())
            root.keys().forEach { k -> map[k] = root.getString(k) }
            Log.i(TAG, "loaded ${map.size} glossary entries")
            map.size
        } catch (t: Throwable) {
            Log.w(TAG, "glossary parse failed: ${t.message}")
            0
        }
    }

    fun apply(text: String): String {
        if (map.isEmpty()) return text
        var out = text
        for ((from, to) in map) {
            out = out.replace(from, to, ignoreCase = true)
        }
        return out
    }

    private companion object {
        const val TAG = "Glossary"
    }
}
