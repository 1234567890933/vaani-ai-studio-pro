package ai.hindidub.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.hindidub.core.LanguageCatalog
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Offline translation with NLLB-200 (INT8 ONNX) + SentencePiece.
 *
 * Tensor contract, read off the actual model files:
 *   encoder  in : input_ids [1,n], attention_mask [1,n]
 *            out: last_hidden_state [1,n,1024]
 *   decoder  in : input_ids [1,s], encoder_attention_mask [1,n], encoder_hidden_states [1,n,1024]
 *            out: logits [1,s,256206]   (+ 48 KV tensors we ignore: we use the no-past export)
 *
 * Tokenisation follows NLLB's own tokenizer:
 *   source  = [src_lang_id] + sentencepiece(text) + [eos(2)]
 *   decoder = starts at [tgt_lang_id]
 * The language ids are *added tokens*: 256047 for eng_Latn, 256068 for hin_Deva. They are
 * read from tokenizer.json at load time, with those two as fallback constants.
 *
 * MEMORY: this is the heaviest stage. Loading encoder and decoder together peaked above
 * 2 GB in my own test environment, so [translateAll] deliberately runs them one after the
 * other — encode every segment, free the encoder, then load the decoder. Peak RAM drops to
 * roughly the size of one session. See MEMORY_STRATEGY.md.
 */
class TranslationEngine(
    private val modelDir: File,
    private val threads: Int = 4,
    private val useNnapi: Boolean = false
) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var tokHandle = 0L
    private val langIds = HashMap<String, Long>()
    private var eosId = EOS_ID

    private class Encoded(val hidden: FloatArray, val shape: LongArray, val srcLen: Int)

    fun load() {
        val spFile = File(modelDir, "sentencepiece.bpe.model")
        require(spFile.exists()) { "Missing ${spFile.absolutePath}" }
        tokHandle = SentencePieceTokenizer.nativeLoad(spFile.absolutePath)
        check(tokHandle != 0L) { "SentencePiece failed to load ${spFile.name}" }

        langIds.putAll(FALLBACK_LANG_IDS)
        val tokJson = File(modelDir, "tokenizer.json")
        if (tokJson.exists()) {
            try {
                val root = JSONObject(tokJson.readText())
                val added = root.optJSONArray("added_tokens")
                if (added != null) {
                    for (i in 0 until added.length()) {
                        val o = added.getJSONObject(i)
                        langIds[o.optString("content")] = o.optLong("id", -1L)
                    }
                }
                Log.i(TAG, "language ids loaded from tokenizer.json (${langIds.size} entries)")
            } catch (t: Throwable) {
                Log.w(TAG, "tokenizer.json parse failed, using fallback ids: ${t.message}")
            }
        }
        langIds["</s>"]?.let { eosId = it }
        Log.i(TAG, "eng_Latn=${langIds["eng_Latn"]} hin_Deva=${langIds["hin_Deva"]} eos=$eosId")
    }

    /** Convenience for one string; opens and closes both sessions. */
    fun translate(text: String, srcLang: String, tgtLang: String): String =
        translateAll(listOf(text), srcLang, tgtLang) { }.firstOrNull() ?: ""

    /**
     * Translates a batch with the sequential-session memory trick.
     * Returns one string per input, in order. A failed segment yields its source text.
     */
    fun translateAll(
        texts: List<String>,
        srcLang: String,
        tgtLang: String,
        onProgress: (Float) -> Unit
    ): List<String> {
        check(tokHandle != 0L) { "call load() first" }
        if (texts.isEmpty()) return emptyList()

        val srcTag = LanguageCatalog.byCode(srcLang)?.nllb ?: srcLang
        val tgtTag = LanguageCatalog.byCode(tgtLang)?.nllb ?: tgtLang
        val tgtId = langIds[tgtTag]
            ?: error("This model has no token for $tgtTag — it cannot output that language")

        // ---- pass 1: encoder ------------------------------------------------
        val encoded = ArrayList<Encoded>(texts.size)
        val enc = openSession(File(modelDir, "encoder.onnx"))
        try {
            texts.forEachIndexed { i, t ->
                val ids = sourceIds(t, srcTag)
                encoded.add(runEncoder(enc, ids))
                onProgress(0.5f * (i + 1) / texts.size)
            }
        } finally {
            enc.close()
        }

        // ---- pass 2: decoder ------------------------------------------------
        val out = ArrayList<String>(texts.size)
        val dec = openSession(File(modelDir, "decoder.onnx"))
        try {
            encoded.forEachIndexed { i, e ->
                out.add(
                    try {
                        decode(dec, e, tgtId)
                    } catch (t: Throwable) {
                        Log.e(TAG, "decode failed for segment $i: ${t.message}")
                        texts[i]
                    }
                )
                onProgress(0.5f + 0.5f * (i + 1) / texts.size)
            }
        } finally {
            dec.close()
        }
        return out
    }

    // ---- internals ---------------------------------------------------------

    private fun openSession(file: File): OrtSession {
        require(file.exists()) { "Missing ${file.absolutePath}" }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            // BASIC, not ALL_OPT: full graph optimisation duplicates weights and was the
            // difference between loading and OOM-ing in my measurements.
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            if (useNnapi) {
                try {
                    addNnapi()
                    Log.i(TAG, "NNAPI provider added")
                } catch (t: Throwable) {
                    Log.w(TAG, "NNAPI unavailable: ${t.message}")
                }
            }
        }
        val session = env.createSession(file.absolutePath, opts)
        Log.i(TAG, "${file.name} in=${session.inputNames} out=${session.outputNames.take(1)}")
        return session
    }

    private fun sourceIds(text: String, srcTag: String): LongArray {
        val srcId = langIds[srcTag] ?: FALLBACK_LANG_IDS["eng_Latn"]!!
        val pieces = SentencePieceTokenizer.nativeEncode(tokHandle, text.trim(), false)
            ?: IntArray(0)
        val ids = LongArray(pieces.size + 2)
        ids[0] = srcId
        for (i in pieces.indices) ids[i + 1] = pieces[i].toLong()
        ids[ids.size - 1] = eosId
        return ids
    }

    private fun runEncoder(session: OrtSession, ids: LongArray): Encoded {
        val n = ids.size
        val inputIds = OnnxTensor.createTensor(
            env, LongBuffer.wrap(ids), longArrayOf(1, n.toLong())
        )
        val mask = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(n) { 1L }), longArrayOf(1, n.toLong())
        )
        try {
            val res = session.run(mapOf("input_ids" to inputIds, "attention_mask" to mask))
            try {
                val t = res.get(0) as OnnxTensor
                val shape = t.info.shape
                val fb = t.floatBuffer
                val data = FloatArray(fb.remaining()).also { fb.get(it) }
                return Encoded(data, shape, n)
            } finally {
                res.close()
            }
        } finally {
            inputIds.close()
            mask.close()
        }
    }

    private fun decode(session: OrtSession, e: Encoded, tgtId: Long): String {
        val hidden = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(e.hidden), e.shape
        )
        val encMask = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(e.srcLen) { 1L }), longArrayOf(1, e.srcLen.toLong())
        )
        val produced = ArrayList<Long>(MAX_NEW_TOKENS)
        produced.add(tgtId)

        try {
            var step = 0
            while (step < MAX_NEW_TOKENS) {
                val cur = produced.toLongArray()
                val decIds = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(cur), longArrayOf(1, cur.size.toLong())
                )
                val res = try {
                    session.run(
                        mapOf(
                            "input_ids" to decIds,
                            "encoder_attention_mask" to encMask,
                            "encoder_hidden_states" to hidden
                        )
                    )
                } finally {
                    decIds.close()
                }

                val next: Int
                try {
                    val logits = res.get("logits") as? OnnxTensor ?: res.get(0) as OnnxTensor
                    val shape = logits.info.shape
                    val seq = shape[shape.size - 2].toInt()
                    val vocab = shape[shape.size - 1].toInt()
                    val buf = logits.floatBuffer
                    val base = (seq - 1) * vocab
                    var best = 0
                    var bestVal = Float.NEGATIVE_INFINITY
                    for (v in 0 until vocab) {
                        val x = buf.get(base + v)
                        if (x > bestVal) {
                            bestVal = x
                            best = v
                        }
                    }
                    next = best
                } finally {
                    res.close()
                }

                if (next.toLong() == eosId) break
                produced.add(next.toLong())
                step++
            }
        } finally {
            hidden.close()
            encMask.close()
        }

        val outIds = produced.drop(1).map { it.toInt() }.toIntArray()
        if (outIds.isEmpty()) return ""
        return SentencePieceTokenizer.nativeDecode(tokHandle, outIds).trim()
    }

    override fun close() {
        if (tokHandle != 0L) {
            SentencePieceTokenizer.nativeFree(tokHandle)
            tokHandle = 0L
        }
    }

    companion object {
        private const val TAG = "MTEngine"
        private const val EOS_ID = 2L
        const val MAX_NEW_TOKENS = 160

        /** Fallback ids verified in NLLB's tokenizer.json. */
        private val FALLBACK_LANG_IDS = mapOf(
            "eng_Latn" to 256047L,
            "hin_Deva" to 256068L
        )

    }
}
