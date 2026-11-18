package com.blurcam.app.pipeline

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class Preview(
    val processed: ImageBitmap,
    val original: ImageBitmap,
    val subjectFound: Boolean,
    val segmentationOk: Boolean = true,
)

/**
 * Renders a single still of the clip with the current look, using the same shader as the final render.
 * Everything runs on one dedicated thread that owns the GL context. Call [close] when done.
 */
class PreviewEngine {

    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private var egl: EglCore? = null
    private var renderer: BokehRenderer? = null
    private var masker: SubjectMasker? = null
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverPath: String? = null

    private var cachedKey: String? = null
    private var cachedScaled: Bitmap? = null
    private var cachedMask: MaskData? = null

    suspend fun render(path: String, timeMs: Long, params: BokehParams): Preview? =
        withContext(dispatcher) {
            runCatching { renderOnThread(path, timeMs, params) }
                .onFailure { Log.e("BlurCam", "preview failed", it) }
                .getOrNull()
        }

    private fun renderOnThread(path: String, timeMs: Long, params: BokehParams): Preview? {
        if (egl == null) {
            egl = EglCore(null, PBUFFER, PBUFFER).also { it.makeCurrent() }
            renderer = BokehRenderer().also { it.init() }
            masker = SubjectMasker(streaming = false)
        }
        val r = renderer!!

        val key = "$path@$timeMs"
        if (key != cachedKey) {
            if (retrieverPath != path) {
                retriever?.release()
                retriever = MediaMetadataRetriever().also { it.setDataSource(path) }
                retrieverPath = path
            }
            val frame = retriever!!.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return null
            val k = minOf(1f, MAX_PREVIEW.toFloat() / max(frame.width, frame.height))
            val pw = max(2, (frame.width * k).roundToInt())
            val ph = max(2, (frame.height * k).roundToInt())
            val scaled = if (k < 1f) Bitmap.createScaledBitmap(frame, pw, ph, true) else frame
            val mask = masker!!.segment(frame)
            if (scaled !== frame) frame.recycle()

            cachedScaled?.recycle()
            cachedScaled = scaled
            cachedMask = mask
            cachedKey = key

            r.uploadFrame(scaled)
            r.uploadMask(mask.width, mask.height, mask.bytes)
        }

        val scaled = cachedScaled!!
        val mask = cachedMask!!
        val presence = presenceFromCoverage(mask.coverage)
        r.draw(scaled.width, scaled.height, params, centerFocus = 1f - presence, flipY = true)
        val processed = r.readPixels(scaled.width, scaled.height)

        // original copy for the "hold to compare" overlay
        val original = scaled.copy(Bitmap.Config.ARGB_8888, false)
        return Preview(processed.asImageBitmap(), original.asImageBitmap(), presence > 0.5f, masker!!.available)
    }

    fun close() {
        executor.execute {
            runCatching { renderer?.release() }
            runCatching { egl?.release() }
            runCatching { masker?.close() }
            runCatching { retriever?.release() }
            cachedScaled = null
        }
        executor.shutdown()
    }

    private companion object {
        const val PBUFFER = 1024
        const val MAX_PREVIEW = 960
    }
}
