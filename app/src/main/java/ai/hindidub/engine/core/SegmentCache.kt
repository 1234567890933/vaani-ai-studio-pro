package ai.hindidub.engine.core

import ai.hindidub.core.Diag
import java.util.LinkedHashMap

/**
 * In-job memoisation.
 *
 * Two things repeat constantly in real content: identical source lines ("Nani?!", "Hai",
 * a character's catchphrase) and their translations. Caching both means the TTS and MT
 * stages skip duplicate work. On an episode with 12 % duplicate lines that is 12 % of two
 * stages saved, and the cache dies with the job so memory stays flat.
 *
 * Bounded LRU — a 2-hour film cannot grow this past [maxEntries].
 */
class SegmentCache(private val maxEntries: Int = 512) {

    private val translations = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > maxEntries
    }

    private val audio = object : LinkedHashMap<String, FloatArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > maxEntries / 4
    }

    var translationHits = 0
        private set
    var audioHits = 0
        private set

    fun translated(source: String): String? =
        translations[source]?.also { translationHits++ }

    fun putTranslation(source: String, target: String) {
        translations[source] = target
    }

    fun audio(text: String): FloatArray? = audio[text]?.also { audioHits++ }

    fun putAudio(text: String, pcm: FloatArray) {
        // Only cache short lines; long ones would dominate the budget for little reuse.
        if (pcm.size <= 24_000 * 6) audio[text] = pcm
    }

    fun stats(): String =
        "cache: ${translations.size} lines, ${audio.size} clips " +
            "(${translationHits + audioHits} hits)"

    fun clear() {
        translations.clear()
        audio.clear()
        Diag.record("SegmentCache", "cleared")
    }
}
