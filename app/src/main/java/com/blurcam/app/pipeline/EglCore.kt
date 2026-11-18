package com.blurcam.app.pipeline

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Minimal EGL/GLES3 wrapper. With a [Surface] it renders to it (video encoder input);
 * with null it creates an off-screen pbuffer of [width]x[height] (used for previews).
 * Must be created and used on ONE thread.
 */
class EglCore(surface: Surface?, width: Int, height: Int) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val windowed = surface != null
        val attribs = ArrayList<Int>().apply {
            add(EGL14.EGL_RED_SIZE); add(8)
            add(EGL14.EGL_GREEN_SIZE); add(8)
            add(EGL14.EGL_BLUE_SIZE); add(8)
            add(EGL14.EGL_ALPHA_SIZE); add(8)
            add(EGL14.EGL_RENDERABLE_TYPE); add(EGLExt.EGL_OPENGL_ES3_BIT_KHR)
            add(EGL14.EGL_SURFACE_TYPE); add(if (windowed) EGL14.EGL_WINDOW_BIT else EGL14.EGL_PBUFFER_BIT)
            if (windowed) { add(EGL_RECORDABLE_ANDROID); add(1) }
            add(EGL14.EGL_NONE)
        }.toIntArray()

        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "No suitable EGL config"
        }
        val config = configs[0]!!

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = if (surface != null) {
            EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        } else {
            EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0
            )
        }
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL surface creation failed" }
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    fun setPresentationTime(nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
    }

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
