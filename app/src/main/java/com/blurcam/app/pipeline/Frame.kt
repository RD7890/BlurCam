package com.blurcam.app.pipeline

import android.graphics.Bitmap

/**
 * One decoded video frame. Either tight planar YUV in STORAGE orientation (+ [rot] clockwise quarter
 * turns that make it upright) or an already-upright RGB [bitmap].
 */
class Frame(
    val sw: Int,
    val sh: Int,
    val rot: Int,
    val y: ByteArray?,
    val u: ByteArray?,
    val v: ByteArray?,
    val bitmap: Bitmap?,
    val bt709: Boolean,
    val fullRange: Boolean,
    var relUs: Long = 0L,
) {
    val upW: Int get() = if (rot % 2 == 1) sh else sw
    val upH: Int get() = if (rot % 2 == 1) sw else sh
    val cw: Int get() = (sw + 1) / 2
    val ch: Int get() = (sh + 1) / 2

    companion object {
        fun ofBitmap(b: Bitmap) = Frame(b.width, b.height, 0, null, null, null, b, true, true)
    }
}
