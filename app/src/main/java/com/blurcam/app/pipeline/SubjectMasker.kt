package com.blurcam.app.pipeline

import android.graphics.Bitmap
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
 */
class SubjectMasker(streaming: Boolean) : AutoCloseable {

    private val segmenter: Segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(
                if (streaming) SelfieSegmenterOptions.STREAM_MODE
                else SelfieSegmenterOptions.SINGLE_IMAGE_MODE
            )
            .enableRawSizeMask()
            .build()
    )

    fun segment(frame: Bitmap): MaskData {
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
            val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(input, 0)))
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
        } finally {
            if (input !== frame) input.recycle()
        }
    }

    override fun close() {
        segmenter.close()
    }
}
