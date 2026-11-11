package ai.hindidub.engine

import ai.hindidub.engine.audio.MediaDecoder
import ai.hindidub.engine.audio.PcmStore
import android.content.Context
import android.net.Uri

/**
 * Thin façade over [MediaDecoder] that produces exactly the two things the engine needs,
 * always inside a bounded time window:
 *  * 16 kHz mono floats for Whisper;
 *  * 24 kHz PCM written straight to a [PcmStore] for the sound bed.
 *
 * Nothing here allocates per-file arrays any more — that was the v4 OOM.
 */
object AudioExtractor {

    const val TARGET_SR = 16000
    const val BED_SR = 24000
    const val WINDOW_MS = 4 * 60_000L

    fun probe(context: Context, uri: Uri): MediaDecoder.TrackInfo =
        MediaDecoder.probe(context, uri)

    /** One window, mono, 16 kHz — the ASR input. */
    fun windowMono16k(context: Context, uri: Uri, fromMs: Long, toMs: Long): FloatArray {
        val w = MediaDecoder.decodeWindow(context, uri, fromMs, toMs)
        if (w.frames <= 0) return FloatArray(0)

        val mono = FloatArray(w.frames)
        val ch = w.channels
        var i = 0
        for (f in 0 until w.frames) {
            var acc = 0f
            for (c in 0 until ch) acc += w.data[i++]
            mono[f] = acc / ch
        }
        return AudioUtils.resampleLinear(mono, w.sampleRate, TARGET_SR)
    }

    /** One window of the original audio bed, resampled to 24 kHz and written to disk. */
    fun writeBedWindow(
        context: Context,
        uri: Uri,
        store: PcmStore,
        fromMs: Long,
        toMs: Long
    ) {
        val w = MediaDecoder.decodeWindow(context, uri, fromMs, toMs)
        if (w.frames <= 0) return

        val ch = w.channels
        val outChannels = store.channels
        val perChannel = Array(minOf(ch, outChannels)) { c ->
            val arr = FloatArray(w.frames)
            for (f in 0 until w.frames) arr[f] = w.data[f * ch + c]
            AudioUtils.resampleLinear(arr, w.sampleRate, BED_SR)
        }
        val frames = perChannel[0].size
        val interleaved = FloatArray(frames * outChannels)
        for (f in 0 until frames) {
            for (c in 0 until outChannels) {
                interleaved[f * outChannels + c] =
                    if (c < perChannel.size) perChannel[c][f] else perChannel[0][f]
            }
        }
        val startFrame = fromMs * BED_SR / 1000L
        store.writeFrames(startFrame, interleaved, frames)
    }

    /** Whole-file mono 16 kHz — used only by the speed test on short clips. */
    fun extractMono16k(context: Context, uri: Uri): FloatArray {
        val info = probe(context, uri)
        val end = if (info.durationMs > 0) info.durationMs else 60_000L
        return windowMono16k(context, uri, 0, minOf(end, 120_000L))
    }
}
