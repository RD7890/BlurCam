package com.blurcam.app.pipeline

import kotlin.math.ln

/** Soft subject mask, 0..255 per pixel (255 = subject / keep sharp). */
class MaskData(val width: Int, val height: Int, val bytes: ByteArray, val coverage: Float)

/** User-facing look settings. */
data class BokehParams(val fStop: Float, val bloom: Boolean) {
    /** 0 (f/16, subtle) … 1 (f/1.4, creamy) */
    val strength: Float
        get() {
            val lo = ln(1.4f) / ln(2f)
            val hi = 4f // log2(16)
            val f = ln(fStop) / ln(2f)
            return ((hi - f) / (hi - lo)).coerceIn(0f, 1f)
        }

    /** Blur radius as a fraction of frame height (so previews match renders at any resolution). */
    val radius: Float get() = 0.004f + 0.030f * strength

    val bloomAmount: Float get() = if (bloom) 0.85f else 0f
}

/** Which subject-presence fallback to use when no person is found. */
fun presenceFromCoverage(coverage: Float): Float = ((coverage - 0.01f) / 0.03f).coerceIn(0f, 1f)
