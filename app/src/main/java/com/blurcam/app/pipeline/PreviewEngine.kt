package com.blurcam.app.pipeline

import android.content.Context
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
    /** where the focus plane was taken from, normalised in the upright frame */
    val focusX: Float,
    val focusY: Float,
    val autoFocus: Boolean,
)

/**
 * Renders one still of the clip with the exact same shaders as the final render.
 * Depth for a given frame is computed once and cached, so changing aperture / bloom / focus is instant.
 * Everything runs on one dedicated thread that owns the GL context. Call [close] when done.
 */
class PreviewEngine(private val ctx: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private var egl: EglCore? = null
    private var gl: BokehGl? = null
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverPath: String? = null

    private var frameKey: String? = null
    private var scaled: Bitmap? = null
    private var norm: NormDepth? = null

    private var uploadedKey: String? = null
    private var map: DepthMap? = null
    private var mapKey: String? = null

    suspend fun render(path: String, timeMs: Long, look: BokehParams, quality: Quality, tap: FocusPoint?): Result<Preview> =
        withContext(dispatcher) {
            runCatching { renderOnThread(path, timeMs, look, quality, tap) }
                .onFailure { Log.e("BlurCam", "preview failed", it) }
        }

    private fun renderOnThread(path: String, timeMs: Long, look: BokehParams, quality: Quality, tap: FocusPoint?): Preview {
        if (egl == null) {
            egl = EglCore(null, PBUFFER, PBUFFER).also { it.makeCurrent() }
            gl = BokehGl().also { it.init() }
        }
        val g = gl!!

        val key = "$path@$timeMs@${quality.depthShortSide}"
        if (key != frameKey) {
            if (retrieverPath != path) {
                retriever?.release()
                retriever = MediaMetadataRetriever().also { it.setDataSource(path) }
                retrieverPath = path
            }
            val frame = retriever!!.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: error("Could not read a frame")
            val k = minOf(1f, MAX_PREVIEW.toFloat() / max(frame.width, frame.height))
            val bmp = if (k < 1f) Bitmap.createScaledBitmap(
                frame, max(2, (frame.width * k).roundToInt()), max(2, (frame.height * k).roundToInt()), true
            ) else frame
            if (bmp !== frame) frame.recycle()
            scaled?.recycle()
            scaled = bmp
            norm = DepthEstimator(ctx, quality.depthShortSide).infer(Frame.ofBitmap(bmp))
            frameKey = key
            uploadedKey = null
            mapKey = null
        }
        val bmp = scaled!!

        val tapKey = if (tap == null) "auto" else "${tap.x},${tap.y}"
        if (mapKey != tapKey) {
            map = DepthEstimator.focus(norm!!, tap)
            mapKey = tapKey
            uploadedKey = null
        }
        val dm = map!!

        if (uploadedKey != "$frameKey|$mapKey") {
            g.setSource(Frame.ofBitmap(bmp))
            g.ingest(bmp.width, bmp.height)
            g.setDepth(dm, dm)
            uploadedKey = "$frameKey|$mapKey"
        }
        g.draw(bmp.width, bmp.height, look, dm.focus, 0f, flipY = true)
        val processed = g.readPixels(bmp.width, bmp.height)
        val original = bmp.copy(Bitmap.Config.ARGB_8888, false)
        return Preview(processed.asImageBitmap(), original.asImageBitmap(), dm.seedX, dm.seedY, tap == null)
    }

    fun close() {
        executor.execute {
            runCatching { gl?.release() }
            runCatching { egl?.release() }
            runCatching { retriever?.release() }
            scaled = null
        }
        executor.shutdown()
    }

    private companion object {
        const val PBUFFER = 1024
        const val MAX_PREVIEW = 960
    }
}
