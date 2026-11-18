package com.blurcam.app.pipeline

import android.content.Context
import android.util.Log

/**
 * Initialises ML Kit by hand (the auto-init content provider is removed in the manifest).
 * Any failure here is swallowed and recorded, so the app always starts; segmentation then
 * degrades to the centre-focus fallback instead of crashing.
 */
object MlKitGate {
    @Volatile var ready = false
        private set
    @Volatile var error: String? = null
        private set

    fun init(ctx: Context) {
        try {
            com.google.mlkit.common.MlKit.initialize(ctx)
            ready = true
        } catch (t: Throwable) {
            error = t.toString()
            Log.e("BlurCam", "ML Kit init failed", t)
        }
    }
}
