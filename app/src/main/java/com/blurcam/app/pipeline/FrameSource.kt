package com.blurcam.app.pipeline

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/** Sequential source of decoded frames. */
interface FrameSource : AutoCloseable {
    val upWidth: Int
    val upHeight: Int
    fun next(): Frame?

    companion object {
        /** Fast hardware decode; falls back to the (slow) retriever if the decoder path fails. */
        fun open(path: String, fps: Int): FrameSource =
            try {
                DecoderSource(path)
            } catch (t: Throwable) {
                Log.e("BlurCam", "Decoder path failed, using retriever fallback", t)
                RetrieverSource(path, fps)
            }
    }
}

/** MediaExtractor + MediaCodec decoding straight to YUV_420_888 images. No seeking, real-time x5+. */
class DecoderSource(path: String) : FrameSource {
    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val rot: Int
    private val sw: Int
    private val sh: Int
    private var bt709: Boolean
    private var fullRange = false
    private var inputDone = false
    private var outputDone = false

    override val upWidth: Int get() = if (rot % 2 == 1) sh else sw
    override val upHeight: Int get() = if (rot % 2 == 1) sw else sh

    init {
        extractor.setDataSource(path)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) { track = i; format = f; break }
        }
        require(track >= 0 && format != null) { "No video track" }
        extractor.selectTrack(track)
        sw = format.getInteger(MediaFormat.KEY_WIDTH)
        sh = format.getInteger(MediaFormat.KEY_HEIGHT)
        rot = ((format.getInteger(MediaFormat.KEY_ROTATION, 0) / 90) % 4 + 4) % 4
        bt709 = standardIs709(format, sh)
        fullRange = format.getInteger(MediaFormat.KEY_COLOR_RANGE, 0) == MediaFormat.COLOR_RANGE_FULL
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()
    }

    private fun standardIs709(f: MediaFormat, height: Int): Boolean {
        return when (f.getInteger(MediaFormat.KEY_COLOR_STANDARD, 0)) {
            MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_STANDARD_BT2020 -> true
            MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> false
            else -> height >= 720
        }
    }

    override fun next(): Frame? {
        var idle = 0
        while (!outputDone) {
            if (!inputDone) {
                val ii = decoder.dequeueInputBuffer(0)
                if (ii >= 0) {
                    val buf = decoder.getInputBuffer(ii)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        decoder.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val oi = decoder.dequeueOutputBuffer(info, 5_000)
            if (oi >= 0) {
                idle = 0
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                var frame: Frame? = null
                if (info.size > 0) {
                    val img = decoder.getOutputImage(oi)
                    if (img != null) {
                        frame = toFrame(img, info.presentationTimeUs)
                        img.close()
                    }
                }
                decoder.releaseOutputBuffer(oi, false)
                if (eos) outputDone = true
                if (frame != null) return frame
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = decoder.outputFormat
                bt709 = standardIs709(of, sh)
                fullRange = of.getInteger(MediaFormat.KEY_COLOR_RANGE, 0) == MediaFormat.COLOR_RANGE_FULL
            } else {
                if (++idle > 4000) error("Decoder stalled")
            }
        }
        return null
    }

    private fun toFrame(img: Image, ptsUs: Long): Frame {
        val w = img.width
        val h = img.height
        val crop = img.cropRect
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val planes = img.planes
        val y = ByteArray(w * h)
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        copyPlane(planes[0], crop.left, crop.top, w, h, y)
        copyPlane(planes[1], crop.left / 2, crop.top / 2, cw, ch, u)
        copyPlane(planes[2], crop.left / 2, crop.top / 2, cw, ch, v)
        return Frame(w, h, rot, y, u, v, null, bt709, fullRange).also { it.relUs = ptsUs }
    }

    private fun copyPlane(p: Image.Plane, x0: Int, y0: Int, w: Int, h: Int, dst: ByteArray) {
        val buf: ByteBuffer = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        if (ps == 1) {
            for (row in 0 until h) {
                buf.position((y0 + row) * rs + x0)
                buf.get(dst, row * w, w)
            }
        } else {
            val need = (w - 1) * ps + 1
            val tmp = ByteArray(need)
            for (row in 0 until h) {
                val start = (y0 + row) * rs + x0 * ps
                buf.position(start)
                val n = min(need, buf.remaining())
                buf.get(tmp, 0, n)
                val o = row * w
                for (x in 0 until w) {
                    val idx = x * ps
                    dst[o + x] = if (idx < n) tmp[idx] else tmp[max(0, n - 1)]
                }
            }
        }
    }

    override fun close() {
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { extractor.release() }
    }
}

/** Slow fallback: seeks per frame through MediaMetadataRetriever. Frames come out upright. */
class RetrieverSource(path: String, private val fps: Int) : FrameSource {
    private val r = MediaMetadataRetriever().also { it.setDataSource(path) }
    private val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    private var n = 0
    private val total = max(1, (durationMs * fps / 1000).toInt())
    private var first: Bitmap? = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)

    override val upWidth: Int = first?.width ?: error("Could not decode the first frame")
    override val upHeight: Int = first!!.height

    override fun next(): Frame? {
        if (n >= total) return null
        val tUs = n * 1_000_000L / fps
        val bmp = if (n == 0) first.also { first = null }
        else r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
        n++
        if (bmp == null) return next()
        return Frame.ofBitmap(bmp).also { it.relUs = tUs }
    }

    override fun close() {
        runCatching { r.release() }
    }
}
