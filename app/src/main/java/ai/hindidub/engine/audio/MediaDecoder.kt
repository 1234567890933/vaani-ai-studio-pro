package ai.hindidub.engine.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder

/**
 * Windowed decoder.
 *
 * Everything is decoded in bounded windows (default 4 minutes) so peak memory does not
 * depend on how long the video is. A 2-hour film costs the same RAM as a 2-minute clip.
 * Each window re-opens the extractor and seeks — ~50–150 ms of overhead per window, which
 * is nothing next to the ASR that follows.
 */
object MediaDecoder {

    private const val TAG = "MediaDecoder"

    data class TrackInfo(
        val durationMs: Long,
        val sampleRate: Int,
        val channels: Int,
        val mime: String,
        val isFloatPcm: Boolean
    )

    data class Window(val data: FloatArray, val frames: Int, val channels: Int, val sampleRate: Int)

    fun probe(context: Context, uri: Uri): TrackInfo {
        val durationMs = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }.getOrElse { 0L }

        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                extractor.setDataSource(it.fileDescriptor)
            } ?: error("Cannot open file")
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    val float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    return TrackInfo(durationMs, sr, ch, mime, float)
                }
            }
            error("No audio track in this file")
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Decodes [fromMs, toMs) of the audio track into interleaved floats at the file's own
     * sample rate. Bounded by the window, never by the file length.
     */
    fun decodeWindow(context: Context, uri: Uri, fromMs: Long, toMs: Long): Window {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                extractor.setDataSource(it.fileDescriptor)
            } ?: error("Cannot open file")

            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) error("No audio track in this file")

            extractor.selectTrack(track)
            if (fromMs > 0) extractor.seekTo(fromMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val isFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sink = FloatSink(1 shl 16)
            val info = MediaCodec.BufferInfo()
            val fromUs = fromMs * 1000L
            val toUs = toMs * 1000L
            var inputDone = false
            var outputDone = false
            // seekTo() lands on a sync frame *before* the request; drop the pre-roll so the
            // window starts exactly at fromMs and segment timestamps stay accurate.
            var pendingDropFrames = -1

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (info.presentationTimeUs <= toUs) {
                            val bytesPerSample = if (isFloat) 4 else 2
                            val frames = info.size / (bytesPerSample * channels)
                            var skip = 0
                            if (pendingDropFrames < 0) {
                                val deltaUs = fromUs - info.presentationTimeUs
                                pendingDropFrames = if (deltaUs > 0) {
                                    (deltaUs * sampleRate / 1_000_000L).toInt()
                                        .coerceIn(0, frames)
                                } else 0
                            }
                            skip = pendingDropFrames.coerceIn(0, frames)
                            pendingDropFrames = 0

                            if (isFloat) {
                                val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                val tmp = FloatArray(fb.remaining())
                                fb.get(tmp)
                                if (tmp.size > skip * channels) {
                                    val from = skip * channels
                                    sink.addAll(tmp.copyOfRange(from, tmp.size))
                                }
                            } else {
                                val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val n = sb.remaining()
                                val tmp = FloatArray(n)
                                for (i in 0 until n) tmp[i] = sb.get() / 32768f
                                if (n > skip * channels) {
                                    val from = skip * channels
                                    sink.addAll(tmp.copyOfRange(from, n))
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }

            val data = sink.toArray()
            val frames = data.size / channels
            Log.i(TAG, "window ${fromMs}-${toMs}ms → $frames frames @ ${sampleRate}Hz/${channels}ch")
            return Window(data, frames, channels, sampleRate)
        } finally {
            codec?.let { c ->
                runCatching { c.stop() }
                runCatching { c.release() }
            }
            runCatching { extractor.release() }
        }
    }
}
