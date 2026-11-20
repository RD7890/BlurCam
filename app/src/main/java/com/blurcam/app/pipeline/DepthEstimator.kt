package com.blurcam.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Normalised disparity 0..1 (1 = nearest), before the subject is flattened. */
class NormDepth(val w: Int, val h: Int, val d: FloatArray)

/** Final depth used by the shaders: the subject is flattened onto one in-focus plane [focus]. */
class DepthMap(val w: Int, val h: Int, val d: FloatArray, val focus: Float, val seedX: Float, val seedY: Float) {
    companion object
}

/**
 * Frame -> relative depth -> temporally stable 0..1 disparity -> focus decision.
 * One instance per render (keeps the running normalisation); call from a single thread.
 */
class DepthEstimator(private val ctx: Context, private val shortSide: Int) {

    private var lo = Float.NaN
    private var hi = Float.NaN

    fun infer(frame: Frame): NormDepth {
        val upW = frame.upW
        val upH = frame.upH
        val inW: Int
        val inH: Int
        if (upW <= upH) { inW = round14(shortSide.toFloat()); inH = round14(shortSide.toFloat() * upH / upW) }
        else { inH = round14(shortSide.toFloat()); inW = round14(shortSide.toFloat() * upW / upH) }

        val chw = if (frame.bitmap != null) prepareBitmap(frame.bitmap, inW, inH) else prepareYuv(frame, inW, inH)
        val raw = DepthModel.infer(ctx, chw, inH, inW)
        return normalise(raw)
    }

    /* ───────────── input preparation (3x3 box average, ImageNet normalisation) ───────────── */

    private fun prepareYuv(f: Frame, W: Int, H: Int): FloatArray {
        val out = FloatArray(3 * W * H)
        val plane = W * H
        val y = f.y!!; val u = f.u!!; val v = f.v!!
        val yOff = if (f.fullRange) 0f else 16f
        val yScale = if (f.fullRange) 1f else 255f / 219f
        val cScale = if (f.fullRange) 1f else 255f / 224f
        val kR = if (f.bt709) 1.5748f else 1.402f
        val kGu = if (f.bt709) 0.1873f else 0.344136f
        val kGv = if (f.bt709) 0.4681f else 0.714136f
        val kB = if (f.bt709) 1.8556f else 1.772f
        val cw = f.cw; val ch = f.ch
        for (py in 0 until H) {
            for (px in 0 until W) {
                var ysum = 0f
                for (l in 0 until 3) {
                    for (k in 0 until 3) {
                        val uu = (px + (k + 0.5f) / 3f) / W
                        val vv = (py + (l + 0.5f) / 3f) / H
                        val sx = (storageS(uu, vv, f.rot) * f.sw).toInt().coerceIn(0, f.sw - 1)
                        val sy = (storageT(uu, vv, f.rot) * f.sh).toInt().coerceIn(0, f.sh - 1)
                        ysum += (y[sy * f.sw + sx].toInt() and 0xFF)
                    }
                }
                val cu = (px + 0.5f) / W
                val cv = (py + 0.5f) / H
                val cx = (storageS(cu, cv, f.rot) * cw).toInt().coerceIn(0, cw - 1)
                val cy = (storageT(cu, cv, f.rot) * ch).toInt().coerceIn(0, ch - 1)
                val yy = (ysum / 9f - yOff) * yScale / 255f
                val uc = ((u[cy * cw + cx].toInt() and 0xFF) / 255f - 0.5f) * cScale
                val vc = ((v[cy * cw + cx].toInt() and 0xFF) / 255f - 0.5f) * cScale
                val r = (yy + kR * vc).coerceIn(0f, 1f)
                val g = (yy - kGu * uc - kGv * vc).coerceIn(0f, 1f)
                val b = (yy + kB * uc).coerceIn(0f, 1f)
                val o = py * W + px
                out[o] = (r - MEAN_R) / STD_R
                out[plane + o] = (g - MEAN_G) / STD_G
                out[2 * plane + o] = (b - MEAN_B) / STD_B
            }
        }
        return out
    }

    private fun prepareBitmap(bmp: Bitmap, W: Int, H: Int): FloatArray {
        val sw = bmp.width; val sh = bmp.height
        val px = IntArray(sw * sh)
        bmp.getPixels(px, 0, sw, 0, 0, sw, sh)
        val out = FloatArray(3 * W * H)
        val plane = W * H
        for (py in 0 until H) {
            for (pxl in 0 until W) {
                var r = 0f; var g = 0f; var b = 0f
                for (l in 0 until 3) {
                    for (k in 0 until 3) {
                        val sx = (((pxl + (k + 0.5f) / 3f) / W) * sw).toInt().coerceIn(0, sw - 1)
                        val sy = (((py + (l + 0.5f) / 3f) / H) * sh).toInt().coerceIn(0, sh - 1)
                        val c = px[sy * sw + sx]
                        r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                    }
                }
                val o = py * W + pxl
                out[o] = (r / 9f / 255f - MEAN_R) / STD_R
                out[plane + o] = (g / 9f / 255f - MEAN_G) / STD_G
                out[2 * plane + o] = (b / 9f / 255f - MEAN_B) / STD_B
            }
        }
        return out
    }

    /* ───────────── normalisation: percentile range, smoothed over time ───────────── */

    private fun normalise(raw: RawDepth): NormDepth {
        val n = raw.data.size
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in raw.data) { if (v < mn) mn = v; if (v > mx) mx = v }
        val span = max(mx - mn, 1e-6f)
        val hist = IntArray(256)
        for (v in raw.data) hist[(((v - mn) / span) * 255f).toInt().coerceIn(0, 255)]++
        fun pct(p: Float): Float {
            val target = (p * n).toInt()
            var acc = 0
            for (i in 0 until 256) { acc += hist[i]; if (acc >= target) return mn + span * i / 255f }
            return mx
        }
        val l = pct(0.02f)
        val h = pct(0.98f)
        lo = if (lo.isNaN()) l else lo * 0.6f + l * 0.4f
        hi = if (hi.isNaN()) h else hi * 0.6f + h * 0.4f
        val range = max(hi - lo, 1e-6f)
        val d = FloatArray(n) { ((raw.data[it] - lo) / range).coerceIn(0f, 1f) }
        return NormDepth(raw.w, raw.h, d)
    }

    companion object {
        private const val MEAN_R = 0.485f; private const val MEAN_G = 0.456f; private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f; private const val STD_G = 0.224f; private const val STD_B = 0.225f

        private fun round14(v: Float) = max(14, (v / 14f).roundToInt() * 14)

        /** Upright (u,v) -> storage (s,t); identical to the GLSL toStorage(). */
        fun storageS(u: Float, v: Float, rot: Int) = when (rot) { 1 -> v; 2 -> 1f - u; 3 -> 1f - v; else -> u }
        fun storageT(u: Float, v: Float, rot: Int) = when (rot) { 1 -> 1f - u; 2 -> 1f - v; 3 -> u; else -> v }

        /**
         * Decide what is in focus and flatten the whole subject onto that plane.
         * [tap] = user chosen point, null = automatic (nearest substantial object near the centre).
         * Region-growing across smooth depth means head + body (or a whole object) stay sharp together.
         */
        fun focus(nd: NormDepth, tap: FocusPoint?): DepthMap {
            val w = nd.w; val h = nd.h; val d = nd.d
            val f0: Float
            val sx: Int
            val sy: Int
            if (tap != null) {
                sx = (tap.x * w).toInt().coerceIn(0, w - 1)
                sy = (tap.y * h).toInt().coerceIn(0, h - 1)
                val win = ArrayList<Float>()
                for (yy in max(0, sy - 2)..min(h - 1, sy + 2)) for (xx in max(0, sx - 2)..min(w - 1, sx + 2)) win.add(d[yy * w + xx])
                win.sort()
                f0 = win[win.size / 2]
            } else {
                f0 = autoFocusDepth(d, w, h)
                val seed = autoSeed(d, w, h, f0)
                sx = seed.first; sy = seed.second
            }
            val (mask, fm) = grow(d, w, h, sx, sy, f0)
            val out = d.copyOf()
            for (i in out.indices) if (mask[i]) out[i] = fm
            return DepthMap(w, h, out, fm, (sx + 0.5f) / w, (sy + 0.5f) / h)
        }

        private fun autoFocusDepth(d: FloatArray, w: Int, h: Int): Float {
            val hist = FloatArray(32)
            val bins = IntArray(d.size)
            var total = 0f
            for (y in 0 until h) for (x in 0 until w) {
                val u = (x + 0.5f) / w; val v = (y + 0.5f) / h
                val i = y * w + x
                bins[i] = min((d[i] * 32f).toInt(), 31)
                if (u > 0.2f && u < 0.8f && v > 0.15f && v < 0.85f) {
                    val a = (u - 0.5f) / 0.25f; val b = (v - 0.5f) / 0.30f
                    val wt = exp(-(a * a + b * b) / 2f)
                    hist[bins[i]] += wt; total += wt
                }
            }
            if (total <= 0f) return 0.5f
            var acc = 0f
            for (k in 31 downTo 0) {
                acc += hist[k] / total
                if (acc >= 0.12f) {
                    val vals = ArrayList<Float>()
                    for (y in 0 until h) for (x in 0 until w) {
                        val u = (x + 0.5f) / w; val v = (y + 0.5f) / h
                        val i = y * w + x
                        if (u > 0.2f && u < 0.8f && v > 0.15f && v < 0.85f && bins[i] >= max(0, k - 2) && bins[i] <= min(31, k + 2)) vals.add(d[i])
                    }
                    if (vals.isNotEmpty()) { vals.sort(); return vals[vals.size / 2] }
                    return (k + 0.5f) / 32f
                }
            }
            return 0.5f
        }

        private fun autoSeed(d: FloatArray, w: Int, h: Int, f: Float): Pair<Int, Int> {
            var best = Float.MAX_VALUE
            var bx = w / 2; var by = h / 2
            for (y in 0 until h) for (x in 0 until w) {
                val u = (x + 0.5f) / w; val v = (y + 0.5f) / h
                if (u < 0.15f || u > 0.85f || v < 0.1f || v > 0.9f) continue
                if (abs(d[y * w + x] - f) >= 0.06f) continue
                val dist = (u - 0.5f) * (u - 0.5f) + (v - 0.5f) * (v - 0.5f)
                if (dist < best) { best = dist; bx = x; by = y }
            }
            return Pair(bx, by)
        }

        private fun grow(d: FloatArray, w: Int, h: Int, sx: Int, sy: Int, f: Float, tau: Float = 0.035f, limit: Float = 0.30f): Pair<BooleanArray, Float> {
            val mask = BooleanArray(w * h)
            if (abs(d[sy * w + sx] - f) > limit) return Pair(mask, f)
            val q = ArrayDeque<Int>()
            mask[sy * w + sx] = true
            q.add(sy * w + sx)
            val dx = intArrayOf(1, -1, 0, 0)
            val dy = intArrayOf(0, 0, 1, -1)
            while (q.isNotEmpty()) {
                val c = q.poll()!!
                val cx = c % w; val cy = c / w
                val v = d[c]
                for (k in 0 until 4) {
                    val nx = cx + dx[k]; val ny = cy + dy[k]
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val ni = ny * w + nx
                    if (mask[ni]) continue
                    val u = d[ni]
                    if (abs(u - v) < tau && abs(u - f) < limit) { mask[ni] = true; q.add(ni) }
                }
            }
            val vals = ArrayList<Float>()
            for (i in mask.indices) if (mask[i]) vals.add(d[i])
            vals.sort()
            return Pair(mask, vals[vals.size / 2])
        }
    }
}
