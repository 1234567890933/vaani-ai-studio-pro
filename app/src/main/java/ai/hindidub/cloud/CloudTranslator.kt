package ai.hindidub.cloud

import ai.hindidub.core.DubLanguage
import ai.hindidub.core.SecretStore
import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Router for the optional cloud providers.
 *
 * Modes (Settings → Translation):
 *  * OFFLINE   — on-device NLLB only. No network, no key needed.
 *  * SINGLE    — one provider, everything through it.
 *  * FAILOVER  — providers tried in order; the first one that answers wins.
 *  * PARALLEL  — segments are split across every configured provider and run at the same
 *                time, then merged in order. This is the "four APIs working together"
 *                behaviour: a 200-segment episode finishes in roughly the time of the
 *                slowest quarter.
 *
 * Reliability: each provider carries a failure count and a cooldown. After 2 consecutive
 * failures it is skipped for 5 minutes, so a dead or rate-limited key cannot stall a job —
 * the router simply moves on. A failed chunk falls back to the offline engine upstream.
 */
class CloudTranslator(private val context: Context) {

    enum class Mode { OFFLINE, SINGLE, FAILOVER, PARALLEL }

    private class Health {
        var failures = 0
        var cooldownUntil = 0L
        var lastLatencyMs = 0L
        val available: Boolean get() = System.currentTimeMillis() > cooldownUntil
    }

    private val health = ConcurrentHashMap<String, Health>()

    fun configuredProviders(order: String): List<TranslationProvider> =
        order.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { Providers.byId(it) }
            .filter { SecretStore.has(context, keyName(it.id)) }

    fun statusLines(order: String): List<String> = Providers.all.map { p ->
        val h = health.getOrPut(p.id) { Health() }
        val key = if (SecretStore.has(context, keyName(p.id))) "key set" else "no key"
        val state = if (!h.available) "cooling down" else "ok"
        "${p.label}: $key, $state" + if (h.lastLatencyMs > 0) ", ${h.lastLatencyMs} ms" else ""
    }

    suspend fun translateAll(
        texts: List<String>,
        src: DubLanguage,
        tgt: DubLanguage,
        mode: Mode,
        order: String,
        onProgress: (Float) -> Unit
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        val providers = configuredProviders(order)
        if (providers.isEmpty()) error("No cloud provider has an API key")
        if (mode == Mode.OFFLINE) error("cloud router called in offline mode")

        val result = arrayOfNulls<String>(texts.size)

        when (mode) {
            Mode.SINGLE -> {
                val p = providers.first()
                chunkedTranslate(p, texts, src, tgt) { i, s -> result[i] = s }
                onProgress(1f)
            }
            Mode.FAILOVER -> {
                var done = 0
                for (p in providers) {
                    val missing = texts.indices.filter { result[it] == null }
                    if (missing.isEmpty()) break
                    try {
                        chunkedTranslate(p, missing.map { texts[it] }, src, tgt) { i, s ->
                            result[missing[i]] = s
                            done++
                            onProgress(done.toFloat() / texts.size)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "${p.id} failed: ${t.message}")
                        markFailure(p.id)
                    }
                }
            }
            Mode.PARALLEL -> {
                val usable = providers.filter { health.getOrPut(it.id) { Health() }.available }
                    .ifEmpty { providers }
                val groups = splitEvenly(texts.indices.toList(), usable.size)
                var done = 0
                coroutineScope {
                    groups.mapIndexed { gi, indices ->
                        val provider = usable[gi % usable.size]
                        async {
                            if (indices.isEmpty()) return@async
                            try {
                                chunkedTranslate(provider, indices.map { texts[it] }, src, tgt) { i, s ->
                                    result[indices[i]] = s
                                    synchronized(this@CloudTranslator) {
                                        done++
                                        onProgress(done.toFloat() / texts.size)
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "${provider.id} chunk failed: ${t.message}")
                                markFailure(provider.id)
                            }
                        }
                    }.awaitAll()
                }
                // Anything still missing: retry serially with the healthiest provider.
                val missing = texts.indices.filter { result[it] == null }
                if (missing.isNotEmpty()) {
                    val fallback = usable.minByOrNull { health.getOrPut(it.id) { Health() }.failures }
                        ?: providers.first()
                    chunkedTranslate(fallback, missing.map { texts[it] }, src, tgt) { i, s ->
                        result[missing[i]] = s
                    }
                }
            }
            Mode.OFFLINE -> Unit
        }

        return texts.indices.map { result[it] ?: texts[it] }
    }

    /** One request per 40 segments keeps prompts inside every provider's context window. */
    private suspend fun chunkedTranslate(
        provider: TranslationProvider,
        texts: List<String>,
        src: DubLanguage,
        tgt: DubLanguage,
        onItem: (Int, String) -> Unit
    ) {
        val key = SecretStore.get(context, keyName(provider.id))
            ?: error("No key for ${provider.label}")
        var index = 0
        while (index < texts.size) {
            val end = minOf(index + CHUNK, texts.size)
            val slice = texts.subList(index, end)
            val started = System.currentTimeMillis()
            var attempt = 0
            while (true) {
                try {
                    val out = provider.translate(slice, src, tgt, key)
                    if (out.size != slice.size) error("size mismatch ${out.size}/${slice.size}")
                    out.forEachIndexed { i, s -> onItem(index + i, s) }
                    val h = health.getOrPut(provider.id) { Health() }
                    h.failures = 0
                    h.lastLatencyMs = System.currentTimeMillis() - started
                    break
                } catch (t: Throwable) {
                    attempt++
                    if (attempt >= 3) {
                        markFailure(provider.id)
                        throw t
                    }
                    delay(1200L * attempt)   // 1.2 s, 2.4 s backoff
                }
            }
            index = end
        }
    }

    private fun markFailure(id: String) {
        val h = health.getOrPut(id) { Health() }
        h.failures++
        if (h.failures >= 2) {
            h.cooldownUntil = System.currentTimeMillis() + 5 * 60_000L
            Log.w(TAG, "$id cooling down for 5 min after ${h.failures} failures")
        }
    }

    private fun splitEvenly(items: List<Int>, parts: Int): List<List<Int>> {
        if (parts <= 0) return listOf(items)
        val out = ArrayList<MutableList<Int>>(parts)
        repeat(parts) { out.add(ArrayList()) }
        items.forEachIndexed { i, v -> out[i % parts].add(v) }
        return out
    }

    companion object {
        private const val TAG = "CloudRouter"
        private const val CHUNK = 40

        fun keyName(providerId: String) = "apikey.$providerId"
    }
}
