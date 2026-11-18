package com.blurcam.app.pipeline

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GPU depth-of-field: golden-angle disc kernel, only BACKGROUND samples contribute to the blur
 * (so the subject never bleeds into the backdrop = no halos), optional highlight bloom.
 * GL calls must happen on the thread that owns the current EGL context.
 */
class BokehRenderer {

    private var program = 0
    private var frameTex = 0
    private var maskTex = 0
    private var maskBuf: ByteBuffer? = null

    private var uAspect = 0
    private var uRadius = 0
    private var uBloom = 0
    private var uCenterFocus = 0
    private var uFlip = 0

    fun init() {
        program = link(VERTEX, FRAGMENT)
        val tex = IntArray(2)
        GLES30.glGenTextures(2, tex, 0)
        frameTex = tex[0]
        maskTex = tex[1]
        for (t in tex) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uFrame"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uMask"), 1)
        uAspect = GLES30.glGetUniformLocation(program, "uAspect")
        uRadius = GLES30.glGetUniformLocation(program, "uRadius")
        uBloom = GLES30.glGetUniformLocation(program, "uBloom")
        uCenterFocus = GLES30.glGetUniformLocation(program, "uCenterFocus")
        uFlip = GLES30.glGetUniformLocation(program, "uFlip")
    }

    fun uploadFrame(bitmap: Bitmap) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    fun uploadMask(width: Int, height: Int, bytes: ByteArray) {
        var buf = maskBuf
        if (buf == null || buf.capacity() != bytes.size) {
            buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            maskBuf = buf
        }
        buf!!.clear()
        buf.put(bytes)
        buf.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf
        )
    }

    /** Draw to the current surface. [flipY] is true for off-screen read-back previews. */
    fun draw(outW: Int, outH: Int, params: BokehParams, centerFocus: Float, flipY: Boolean) {
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glUniform1f(uAspect, outH.toFloat() / outW.toFloat())
        GLES30.glUniform1f(uRadius, params.radius)
        GLES30.glUniform1f(uBloom, params.bloomAmount)
        GLES30.glUniform1f(uCenterFocus, centerFocus.coerceIn(0f, 1f))
        GLES30.glUniform1f(uFlip, if (flipY) -1f else 1f)
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
        if (program != 0) GLES30.glDeleteProgram(program)
        GLES30.glDeleteTextures(2, intArrayOf(frameTex, maskTex), 0)
        program = 0
    }

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

    private companion object {
        const val VERTEX = """#version 300 es
uniform float uFlip;
out vec2 vUv;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0 - 1.0;
    gl_Position = vec4(p, 0.0, 1.0);
    vUv = vec2(p.x * 0.5 + 0.5, 0.5 - 0.5 * p.y * uFlip);
}
"""

        const val FRAGMENT = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;

uniform sampler2D uFrame;
uniform sampler2D uMask;
uniform float uAspect;       // outH / outW
uniform float uRadius;       // blur radius, fraction of frame height
uniform float uBloom;        // 0..1 highlight emphasis
uniform float uCenterFocus;  // 0..1 fallback when no person is found

// 1 = keep sharp, 0 = fully background
float subject(vec2 uv) {
    float m = texture(uMask, uv).r;
    vec2 d = (uv - vec2(0.5, 0.52)) / vec2(0.30, 0.40);
    float e = 1.0 - smoothstep(0.85, 1.15, length(d));
    return max(m, e * uCenterFocus);
}

void main() {
    vec4 base = texture(uFrame, vUv);
    float s = subject(vUv);
    float amount = 1.0 - smoothstep(0.35, 0.80, s);
    if (amount < 0.01 || uRadius < 0.0005) {
        outColor = vec4(base.rgb, 1.0);
        return;
    }
    float r = uRadius * amount;
    const int N = 56;
    const float GOLDEN = 2.39996323;
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < N; i++) {
        float t = (float(i) + 0.5) / float(N);
        float rad = sqrt(t);
        float a = float(i) * GOLDEN;
        vec2 off = vec2(cos(a) * uAspect, sin(a)) * rad * r;
        vec2 uv = vUv + off;
        vec3 c = texture(uFrame, uv).rgb;
        float bg = 1.0 - smoothstep(0.35, 0.80, subject(uv));
        float luma = dot(c, vec3(0.299, 0.587, 0.114));
        float w = bg * (1.0 + uBloom * 8.0 * pow(luma, 4.0));
        acc += c * w;
        wsum += w;
    }
    vec3 blurred = wsum > 0.0001 ? acc / wsum : base.rgb;
    outColor = vec4(mix(base.rgb, blurred, smoothstep(0.0, 0.25, amount)), 1.0);
}
"""
    }
}
