package com.blurcam.app.pipeline

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline renderer: decode frame -> person matte -> GPU bokeh -> H.264 encode, then copy the
 * original audio track across. Blocking; run it on its own thread (GL context is thread-bound).
 */
object VideoRenderer {

    fun render(
        src: File,
        dst: File,
        params: BokehParams,
        shortSide: Int,
        ensureActive: () -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val retriever = MediaMetadataRetriever()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null
        var egl: EglCore? = null
        var renderer: BokehRenderer? = null
        var masker: SubjectMasker? = null
        var audioEx: MediaExtractor? = null
        var muxerStarted = false
        var muxerStopped = false
        var videoTrack = -1
        var audioTrack = -1
        var frame: Bitmap? = null

        try {
            retriever.setDataSource(src.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("Unreadable clip")
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull()
            val fps = if (frameCount != null && frameCount > 1 && durationMs > 0)
                (frameCount * 1000f / durationMs).roundToInt().coerceIn(15, 30) else 30
            val total = max(1, (durationMs * fps / 1000).toInt())

            val first = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: error("Could not decode the first frame")
            frame = first

            val srcShort = min(first.width, first.height)
            val scale = min(1f, shortSide.toFloat() / srcShort)
            val outW = even(first.width * scale)
            val outH = even(first.height * scale)

            // Original audio (copied untouched after the video is encoded)
            val ex = MediaExtractor().also { it.setDataSource(src.absolutePath) }
            var audioIdx = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioIdx = i; break }
            }
            val audioFormat: MediaFormat? = if (audioIdx >= 0) {
                ex.selectTrack(audioIdx); audioEx = ex; ex.getTrackFormat(audioIdx)
            } else { ex.release(); null }

            // Encoder
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (outW * outH * fps * 0.14f).toInt().coerceIn(2_000_000, 24_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()

            val mux = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            egl = EglCore(inputSurface, outW, outH).also { it.makeCurrent() }
            renderer = BokehRenderer().also { it.init() }
            masker = SubjectMasker(streaming = true)

            val info = MediaCodec.BufferInfo()

            fun drain(eos: Boolean) {
                var idleSpins = 0
                while (true) {
                    val i = enc.dequeueOutputBuffer(info, if (eos) 10_000L else 0L)
                    when {
                        i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!eos) return
                            if (++idleSpins > 400) return
                        }
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            videoTrack = mux.addTrack(enc.outputFormat)
                            if (audioFormat != null) audioTrack = mux.addTrack(audioFormat)
                            mux.start()
                            muxerStarted = true
                        }
                        i >= 0 -> {
                            val buf = enc.getOutputBuffer(i)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (buf != null && info.size > 0 && muxerStarted) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                mux.writeSampleData(videoTrack, buf, info)
                            }
                            enc.releaseOutputBuffer(i, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            var prevMask: ByteArray? = null
            var prevW = 0
            var prevH = 0
            var presence = -1f

            for (n in 0 until total) {
                ensureActive()
                val tUs = n * 1_000_000L / fps
                if (n > 0) {
                    val f = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (f != null) {
                        frame?.recycle()
                        frame = f
                    }
                }
                val cur = frame!!
                val m = masker.segment(cur)

                val pm = prevMask
                val smooth = if (pm != null && prevW == m.width && prevH == m.height) {
                    ByteArray(m.bytes.size) {
                        (((pm[it].toInt() and 0xFF) + (m.bytes[it].toInt() and 0xFF)) / 2).toByte()
                    }
                } else m.bytes
                prevMask = smooth; prevW = m.width; prevH = m.height

                val target = presenceFromCoverage(m.coverage)
                presence = if (presence < 0f) target else presence * 0.8f + target * 0.2f

                renderer.uploadFrame(cur)
                renderer.uploadMask(m.width, m.height, smooth)
                renderer.draw(outW, outH, params, centerFocus = 1f - presence, flipY = false)
                egl.setPresentationTime(tUs * 1000L)
                egl.swapBuffers()
                drain(false)
                onProgress(n + 1, total)
            }

            enc.signalEndOfInputStream()
            drain(true)

            // Audio passthrough
            val ae = audioEx
            if (ae != null && audioTrack >= 0 && muxerStarted) {
                val buf = ByteBuffer.allocate(512 * 1024)
                val ai = MediaCodec.BufferInfo()
                val limitUs = durationMs * 1000L + 150_000L
                while (true) {
                    ensureActive()
                    val size = ae.readSampleData(buf, 0)
                    if (size < 0) break
                    val t = ae.sampleTime
                    if (t > limitUs) break
                    val flags = if (ae.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    ai.set(0, size, t, flags)
                    mux.writeSampleData(audioTrack, buf, ai)
                    ae.advance()
                }
            }

            check(muxerStarted) { "Encoder produced no output" }
            mux.stop()
            muxerStopped = true
        } finally {
            runCatching { renderer?.release() }
            runCatching { egl?.release() }
            runCatching { masker?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            runCatching { audioEx?.release() }
            runCatching { if (muxerStarted && !muxerStopped) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { frame?.recycle() }
            runCatching { retriever.release() }
        }
    }

    private fun even(v: Float): Int {
        val i = v.roundToInt()
        return max(2, i - (i % 2))
    }
}
