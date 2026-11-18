package com.blurcam.app.pipeline

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Person matte from ML Kit Selfie Segmentation (bundled model, works offline).
 * Call [segment] from a background thread only (it blocks on the ML task).
 * If ML Kit is unavailable, [available] is false and [segment] returns an empty matte
 * (the renderer then uses its centre-focus fallback).
 */
class SubjectMasker(streaming: Boolean) : AutoCloseable {

    private val segmenter: Segmenter? = try {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(
                    if (streaming) SelfieSegmenterOptions.STREAM_MODE
                    else SelfieSegmenterOptions.SINGLE_IMAGE_MODE
                )
                .enableRawSizeMask()
                .build()
        )
    } catch (t: Throwable) {
        Log.e("BlurCam", "Segmenter unavailable", t)
        null
    }

    @Volatile
    var available: Boolean = segmenter != null
        private set

    fun segment(frame: Bitmap): MaskData {
        val seg = segmenter ?: return EMPTY
        val scale = 320f / min(frame.width, frame.height)
        val input = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).roundToInt().coerceAtLeast(1),
                (frame.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else frame

        try {
            val mask = Tasks.await(seg.process(InputImage.fromBitmap(input, 0)))
            val w = mask.width
            val h = mask.height
            val buf = mask.buffer
            buf.rewind()
            val out = ByteArray(w * h)
            var fg = 0
            for (i in out.indices) {
                val v = buf.float.coerceIn(0f, 1f)
                if (v > 0.5f) fg++
                out[i] = (v * 255f).roundToInt().toByte()
            }
            return MaskData(w, h, out, fg.toFloat() / out.size)
        } catch (e: InterruptedException) {
            throw e
        } catch (t: Throwable) {
            if (available) Log.e("BlurCam", "Segmentation failed, using centre focus", t)
            available = false
            return EMPTY
        } finally {
            if (input !== frame) input.recycle()
        }
    }

    override fun close() {
        runCatching { segmenter?.close() }
    }

    private companion object {
        val EMPTY = MaskData(1, 1, ByteArray(1), 0f)
    }
}
