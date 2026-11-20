package com.blurcam.app.pipeline

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max

/**
 * Three-pass GPU depth of field:
 *   1. ingest : planar YUV / bitmap -> upright RGB (FBO, mipmapped)
 *   2. coc    : interpolated depth + joint-bilateral upsample -> circle of confusion (FBO)
 *   3. gather : halo-free disc blur into the current framebuffer (encoder surface or pbuffer)
 * Must be used on the thread that owns the current EGL context.
 */
class BokehGl {

    private var pIngest = 0
    private var pCoc = 0
    private var pGather = 0
    private val locs = HashMap<String, Int>()

    private var texY = 0; private var texU = 0; private var texV = 0; private var texBmp = 0
    private var texRgb = 0; private var texCoc = 0; private var texDA = 0; private var texDB = 0
    private var fboRgb = 0; private var fboCoc = 0
    private var fboW = 0; private var fboH = 0

    private var bufY: ByteBuffer? = null
    private var bufU: ByteBuffer? = null
    private var bufV: ByteBuffer? = null
    private var bufD: ByteBuffer? = null

    private var srcIsYuv = true
    private var srcRot = 0
    private var srcBt709 = true
    private var srcFull = false

    var depthW = 1
        private set
    var depthH = 1
        private set

    fun init() {
        pIngest = link(Shaders.VERTEX, Shaders.INGEST)
        pCoc = link(Shaders.VERTEX, Shaders.COC)
        pGather = link(Shaders.VERTEX, Shaders.GATHER)
        val t = IntArray(8)
        GLES30.glGenTextures(8, t, 0)
        texY = t[0]; texU = t[1]; texV = t[2]; texBmp = t[3]
        texRgb = t[4]; texCoc = t[5]; texDA = t[6]; texDB = t[7]
        for (tex in intArrayOf(texY, texU, texV, texBmp, texCoc)) setup(tex, GLES30.GL_LINEAR, GLES30.GL_LINEAR)
        setup(texRgb, GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_LINEAR)
        setup(texDA, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
        setup(texDB, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texBmp)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        for (tex in intArrayOf(texY, texU, texV)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null)
        }
        val f = IntArray(2)
        GLES30.glGenFramebuffers(2, f, 0)
        fboRgb = f[0]; fboCoc = f[1]
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    }

    /* ───────────────────────── uploads ───────────────────────── */

    fun setSource(frame: Frame) {
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val bmp = frame.bitmap
        if (bmp != null) {
            srcIsYuv = false
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texBmp)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            return
        }
        srcIsYuv = true
        srcRot = frame.rot
        srcBt709 = frame.bt709
        srcFull = frame.fullRange
        bufY = plane(bufY, frame.y!!, texY, 0, frame.sw, frame.sh)
        bufU = plane(bufU, frame.u!!, texU, 1, frame.cw, frame.ch)
        bufV = plane(bufV, frame.v!!, texV, 2, frame.cw, frame.ch)
    }

    private fun plane(old: ByteBuffer?, data: ByteArray, tex: Int, unit: Int, w: Int, h: Int): ByteBuffer {
        val buf = if (old != null && old.capacity() == data.size) old
        else ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
        buf.clear(); buf.put(data); buf.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
        return buf
    }

    fun setDepth(a: DepthMap, b: DepthMap) {
        depthW = a.w; depthH = a.h
        upload(texDA, a)
        upload(texDB, b)
    }

    private fun upload(tex: Int, m: DepthMap) {
        var buf = bufD
        if (buf == null || buf.capacity() != m.d.size * 4) {
            buf = ByteBuffer.allocateDirect(m.d.size * 4).order(ByteOrder.nativeOrder())
            bufD = buf
        }
        buf!!.clear()
        buf.asFloatBuffer().put(m.d)
        buf.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, m.w, m.h, 0, GLES30.GL_RED, GLES30.GL_FLOAT, buf)
    }

    /* ───────────────────────── passes ───────────────────────── */

    private fun ensureTargets(w: Int, h: Int) {
        if (w == fboW && h == fboH) return
        fboW = w; fboH = h
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texRgb)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCoc)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null)
        attach(fboRgb, texRgb)
        attach(fboCoc, texCoc)
    }

    private fun attach(fbo: Int, tex: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete" }
    }

    /** Pass 1. Renders the current source into an upright RGB texture of [w]x[h] and builds its mips. */
    fun ingest(w: Int, h: Int) {
        ensureTargets(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboRgb)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(pIngest)
        bind(GLES30.GL_TEXTURE0, texY); bind(GLES30.GL_TEXTURE1, texU); bind(GLES30.GL_TEXTURE2, texV); bind(GLES30.GL_TEXTURE3, texBmp)
        GLES30.glUniform1f(u(pIngest, "uFlip"), -1f)
        GLES30.glUniform1i(u(pIngest, "uMode"), if (srcIsYuv) 0 else 1)
        GLES30.glUniform1i(u(pIngest, "uRot"), srcRot)
        GLES30.glUniform1i(u(pIngest, "uY"), 0)
        GLES30.glUniform1i(u(pIngest, "uU"), 1)
        GLES30.glUniform1i(u(pIngest, "uV"), 2)
        GLES30.glUniform1i(u(pIngest, "uRgb"), 3)
        if (srcFull) {
            GLES30.glUniform4f(u(pIngest, "uYuvA"), 0f, 1f, 1f, 0f)
        } else {
            GLES30.glUniform4f(u(pIngest, "uYuvA"), 16f / 255f, 255f / 219f, 255f / 224f, 0f)
        }
        if (srcBt709) GLES30.glUniform4f(u(pIngest, "uYuvB"), 1.5748f, 0.1873f, 0.4681f, 1.8556f)
        else GLES30.glUniform4f(u(pIngest, "uYuvB"), 1.402f, 0.344136f, 0.714136f, 1.772f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        bind(GLES30.GL_TEXTURE4, texRgb)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * Passes 2+3 into the CURRENT default framebuffer. [mix] blends depth A -> B, [focus] is the matching
     * (interpolated) focus disparity. [flipY] = true for off-screen read-back.
     */
    fun draw(w: Int, h: Int, look: BokehParams, focus: Float, mix: Float, flipY: Boolean) {
        // pass 2: CoC
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboCoc)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(pCoc)
        bind(GLES30.GL_TEXTURE4, texRgb); bind(GLES30.GL_TEXTURE5, texDA); bind(GLES30.GL_TEXTURE6, texDB)
        GLES30.glUniform1f(u(pCoc, "uFlip"), -1f)
        GLES30.glUniform1i(u(pCoc, "uRgb"), 4)
        GLES30.glUniform1i(u(pCoc, "uDepthA"), 5)
        GLES30.glUniform1i(u(pCoc, "uDepthB"), 6)
        GLES30.glUniform2f(u(pCoc, "uDepthSize"), depthW.toFloat(), depthH.toFloat())
        GLES30.glUniform1f(u(pCoc, "uMix"), mix)
        GLES30.glUniform1f(u(pCoc, "uFocus"), focus)
        GLES30.glUniform1f(u(pCoc, "uDof"), look.dof)
        GLES30.glUniform1f(u(pCoc, "uSlope"), look.slope)
        GLES30.glUniform1f(u(pCoc, "uLod"), max(0f, ln(h.toFloat() / depthH) / ln(2f)))
        GLES30.glUniform1f(u(pCoc, "uSigma"), 0.07f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // pass 3: gather into the surface
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(pGather)
        bind(GLES30.GL_TEXTURE4, texRgb); bind(GLES30.GL_TEXTURE7, texCoc)
        GLES30.glUniform1f(u(pGather, "uFlip"), if (flipY) -1f else 1f)
        GLES30.glUniform1i(u(pGather, "uRgb"), 4)
        GLES30.glUniform1i(u(pGather, "uCoc"), 7)
        GLES30.glUniform1f(u(pGather, "uAspect"), h.toFloat() / w.toFloat())
        GLES30.glUniform1f(u(pGather, "uRmax"), look.maxRadius)
        GLES30.glUniform1f(u(pGather, "uHeightPx"), h.toFloat())
        GLES30.glUniform1f(u(pGather, "uBloom"), look.bloomAmount)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun readPixels(w: Int, h: Int): Bitmap {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.position(0)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    fun release() {
        for (p in intArrayOf(pIngest, pCoc, pGather)) if (p != 0) GLES30.glDeleteProgram(p)
        GLES30.glDeleteTextures(8, intArrayOf(texY, texU, texV, texBmp, texRgb, texCoc, texDA, texDB), 0)
        GLES30.glDeleteFramebuffers(2, intArrayOf(fboRgb, fboCoc), 0)
        pIngest = 0; pCoc = 0; pGather = 0
    }

    /* ───────────────────────── helpers ───────────────────────── */

    private fun bind(unit: Int, tex: Int) {
        GLES30.glActiveTexture(unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    }

    private fun setup(tex: Int, min: Int, mag: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, min)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, mag)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun u(program: Int, name: String): Int =
        locs.getOrPut("$program:$name") { GLES30.glGetUniformLocation(program, name) }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { "Program link failed: " + GLES30.glGetProgramInfoLog(p) }
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { "Shader compile failed: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }
}
