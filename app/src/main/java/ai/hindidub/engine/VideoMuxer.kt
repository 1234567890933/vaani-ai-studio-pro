package ai.hindidub.engine

import ai.hindidub.engine.audio.PcmStore
import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Replaces the video's audio with the dubbed track.
 *
 * Video samples are copied, never re-encoded — identical quality, seconds instead of minutes.
 * Audio is encoded to AAC-LC from the [PcmStore] in blocks and spooled to a temp file with a
 * small index, because MediaMuxer needs every track added before `start()` while the encoder
 * only reveals its output format after the first block. Peak RAM: one block.
 */
object VideoMuxer {

    private const val TAG = "Muxer"
    private const val AAC_BITRATE = 96_000
    private const val BLOCK_FRAMES = 8_192

    private class Encoded(val offset: Long, val size: Int, val ptsUs: Long, val flags: Int)

    fun muxFromStore(
        resolver: ContentResolver,
        videoUri: Uri,
        pcm: PcmStore,
        outFile: File,
        bitrateKbps: Int = 96
    ) {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()
        val spool = File(outFile.parentFile, "${outFile.nameWithoutExtension}.aac.tmp")

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var spoolRaf: RandomAccessFile? = null
        try {
            resolver.openFileDescriptor(videoUri, "r")?.use {
                extractor.setDataSource(it.fileDescriptor)
            } ?: error("Cannot open input video")

            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    break
                }
            }
            require(videoTrack >= 0) { "No video track in this file" }
            val videoFormat = extractor.getTrackFormat(videoTrack)
            extractor.selectTrack(videoTrack)

            // ---- 1. encode AAC to a spool file ------------------------------
            val (audioFormat, index) = encodeToSpool(pcm, spool, bitrateKbps)
            spoolRaf = RandomAccessFile(spool, "r")

            // ---- 2. mux -----------------------------------------------------
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vTrack = muxer.addTrack(videoFormat)
            val aTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var videoSamples = 0
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(vTrack, buffer, info)
                videoSamples++
                if (!extractor.advance()) break
            }

            val chunk = ByteArray(64 * 1024)
            for (e in index) {
                spoolRaf.seek(e.offset)
                var left = e.size
                val bb = ByteBuffer.allocate(e.size)
                while (left > 0) {
                    val n = spoolRaf.read(chunk, 0, minOf(chunk.size, left))
                    if (n <= 0) break
                    bb.put(chunk, 0, n)
                    left -= n
                }
                bb.flip()
                val bi = MediaCodec.BufferInfo()
                bi.set(0, e.size, e.ptsUs, e.flags)
                muxer.writeSampleData(aTrack, bb, bi)
            }

            Log.i(TAG, "muxed $videoSamples video samples + ${index.size} audio samples")
            muxer.stop()
        } finally {
            runCatching { spoolRaf?.close() }
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
            if (spool.exists()) spool.delete()
        }
    }

    private fun encodeToSpool(
        pcm: PcmStore,
        spool: File,
        bitrateKbps: Int
    ): Pair<MediaFormat, List<Encoded>> {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, pcm.channels
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val index = ArrayList<Encoded>(1024)
        val info = MediaCodec.BufferInfo()
        var outFormat: MediaFormat = format
        var frame = 0L
        val totalFrames = pcm.frameCount()

        RandomAccessFile(spool, "rw").use { out ->
            out.setLength(0)
            var position = 0L
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val capacityFrames = buf.remaining() / (2 * pcm.channels)
                        val want = minOf(capacityFrames, BLOCK_FRAMES)
                        val samples = pcm.readFrames(frame, want)
                        val framesRead = samples.size / pcm.channels
                        val ptsUs = frame * 1_000_000L / pcm.sampleRate
                        if (framesRead <= 0 || frame >= totalFrames) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                            for (s in samples) {
                                sb.put((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                            }
                            codec.queueInputBuffer(inIdx, 0, framesRead * pcm.channels * 2, ptsUs, 0)
                            frame += framesRead
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = codec.outputFormat
                } else if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        out.seek(position)
                        out.write(data)
                        index.add(Encoded(position, data.size, info.presentationTimeUs, info.flags))
                        position += data.size
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }

        codec.stop()
        codec.release()
        Log.i(TAG, "AAC encoded ${index.size} chunks @ ${pcm.sampleRate}Hz")
        return outFormat to index
    }
}
