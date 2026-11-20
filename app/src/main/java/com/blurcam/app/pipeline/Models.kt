package com.blurcam.app.pipeline

import kotlin.math.ln

/** User-facing look. All distances are fractions of frame height so previews match renders at any size. */
data class BokehParams(val fStop: Float, val bloom: Boolean) {
    /** 0 (f/16, subtle) … 1 (f/1.4, creamy) */
    val strength: Float
        get() {
            val lo = ln(1.4f) / ln(2f)
            val hi = 4f // log2(16)
            val f = ln(fStop) / ln(2f)
            return ((hi - f) / (hi - lo)).coerceIn(0f, 1f)
        }

    /** Largest blur radius (fraction of frame height). */
    val maxRadius: Float get() = 0.004f + 0.030f * strength

    /** Half width of the in-focus zone, in normalised disparity units. Wider aperture = thinner zone. */
    val dof: Float get() = 0.04f + 0.10f * (1f - strength)

    /** Disparity span over which blur ramps from 0 to max. */
    val slope: Float get() = 0.5f

    val bloomAmount: Float get() = if (bloom) 0.8f else 0f
}

/** Speed / quality profiles. Depth runs at a low frame rate and is interpolated in between. */
enum class Quality(val label: String, val caption: String, val outShortSide: Int, val depthShortSide: Int, val depthStride: Int) {
    FAST("Fast", "720p · quick render", 720, 196, 8),
    BEST("Best", "1080p · finer depth", 1080, 266, 4),
}

/** Normalised (0..1) position in the upright frame. */
data class FocusPoint(val x: Float, val y: Float)

data class RenderParams(val look: BokehParams, val quality: Quality, val focus: FocusPoint?)
