package com.blurcam.app.pipeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline cinematic renderer.
 *
 *   thread A (decode + depth): MediaCodec decode -> depth key-frames every N frames -> segments
 *   thread B (this thread, owns GL): ingest -> CoC -> gather -> H.264 encoder surface -> muxer (+ original audio)
 *
 * The two threads overlap, so total time is roughly max(depth, render) instead of their sum.
 */
object VideoRenderer {

    private const val OUT_FPS = 30

    private class Segment(val frames: List<Frame>, val a: DepthMap, val b: DepthMap, val last: Boolean)
    private class Failure(val t: Throwable)
    private object End

    fun render(
        ctx: Context,
        src: File,
        dst: File,
        params: RenderParams,
        ensureActive: () -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val retriever = MediaMetadataRetriever()
        var source: FrameSource? = null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null
        var egl: EglCore? = null
        var gl: BokehGl? = null
        var audioEx: MediaExtractor? = null
        var producer: Thread? = null
        val stop = AtomicBoolean(false)
        var muxerStarted = false
        var muxerStopped = false
        var videoTrack = -1
        var audioTrack = -1

        try {
            retriever.setDataSource(src.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("Unreadable clip")
            val total = max(1, (durationMs * OUT_FPS / 1000).toInt())

            val s = FrameSource.open(src.absolutePath, OUT_FPS)
            source = s
            val upW = s.upWidth
            val upH = s.upHeight
            val q = params.quality
            val scale = min(1f, q.outShortSide.toFloat() / min(upW, upH))
            val outW = even(upW * scale)
            val outH = even(upH * scale)

            // original audio, copied untouched at the end
            val ex = MediaExtractor().also { it.setDataSource(src.absolutePath) }
            var audioIdx = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioIdx = i; break }
            }
            val audioFormat: MediaFormat? = if (audioIdx >= 0) {
                ex.selectTrack(audioIdx); audioEx = ex; ex.getTrackFormat(audioIdx)
            } else { ex.release(); null }

            // encoder
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (outW * outH * OUT_FPS * 0.14f).toInt().coerceIn(2_000_000, 24_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, OUT_FPS)
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
            val g = BokehGl().also { it.init() }
            gl = g

            // ───────── producer: decode + depth ─────────
            val queue = ArrayBlockingQueue<Any>(2)
            fun put(item: Any): Boolean {
                while (!stop.get()) if (queue.offer(item, 100, TimeUnit.MILLISECONDS)) return true
                return false
            }
            producer = Thread({
                try {
                    val est = DepthEstimator(ctx, q.depthShortSide)
                    val stride = q.depthStride
                    val interval = 1_000_000L / OUT_FPS
                    var firstPts = -1L
                    var nextKeep = 0L
                    var prev: DepthMap? = null
                    var buffer = ArrayList<Frame>()
                    while (!stop.get()) {
                        val f = s.next() ?: break
                        if (firstPts < 0) firstPts = f.relUs
                        val rel = f.relUs - firstPts
                        if (rel < nextKeep - interval / 4) continue
                        nextKeep += interval
                        f.relUs = rel
                        if (prev == null) {
                            prev = DepthMap.from(est, f, params.focus)
                            buffer.add(f)
                            continue
                        }
                        buffer.add(f)
                        if (buffer.size - 1 == stride) {
                            val d = DepthMap.from(est, f, params.focus)
                            if (!put(Segment(buffer, prev, d, false))) return@Thread
                            buffer = arrayListOf(f)
                            prev = d
                        }
                    }
                    val p = prev
                    if (p != null && !stop.get()) {
                        if (buffer.size > 1) {
                            val d = DepthMap.from(est, buffer.last(), params.focus)
                            put(Segment(buffer, p, d, true))
                        } else {
                            put(Segment(buffer, p, p, true))
                        }
                    }
                    put(End)
                } catch (t: Throwable) {
                    put(Failure(t))
                }
            }, "blurcam-depth").also { it.start() }

            // ───────── consumer: GL + encode ─────────
            val info = MediaCodec.BufferInfo()

            fun drain(eos: Boolean) {
                var idle = 0
                while (true) {
                    val i = enc.dequeueOutputBuffer(info, if (eos) 10_000L else 0L)
                    when {
                        i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!eos) return
                            if (++idle > 400) return
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

            var done = 0
            loop@ while (true) {
                ensureActive()
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                when (item) {
                    is End -> break@loop
                    is Failure -> throw item.t
                    is Segment -> {
                        g.setDepth(item.a, item.b)
                        val n = item.frames.size - 1
                        val upto = if (item.last) n else n - 1
                        for (j in 0..upto) {
                            ensureActive()
                            val t = if (n == 0) 0f else j.toFloat() / n
                            val fr = item.frames[j]
                            g.setSource(fr)
                            g.ingest(outW, outH)
                            val focus = item.a.focus + (item.b.focus - item.a.focus) * t
                            g.draw(outW, outH, params.look, focus, t, flipY = false)
                            egl.setPresentationTime(fr.relUs * 1000L)
                            egl.swapBuffers()
                            drain(false)
                            done++
                            onProgress(min(done, total), total)
                        }
                    }
                }
            }

            enc.signalEndOfInputStream()
            drain(true)

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
            stop.set(true)
            runCatching { producer?.interrupt() }
            runCatching { producer?.join(3000) }
            runCatching { gl?.release() }
            runCatching { egl?.release() }
            runCatching { source?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            runCatching { audioEx?.release() }
            runCatching { if (muxerStarted && !muxerStopped) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { retriever.release() }
        }
    }

    private fun even(v: Float): Int {
        val i = v.roundToInt()
        return max(2, i - (i % 2))
    }
}

/** infer + focus in one call. */
internal fun DepthMap.Companion.from(est: DepthEstimator, f: Frame, tap: FocusPoint?): DepthMap =
    DepthEstimator.focus(est.infer(f), tap)
