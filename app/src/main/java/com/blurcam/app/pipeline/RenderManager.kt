package com.blurcam.app.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import java.io.File
import java.util.concurrent.Executors

sealed interface RenderState {
    data object Idle : RenderState
    data class Running(val clip: String, val progress: Float, val etaSec: Int) : RenderState
    data class Done(val clip: String, val file: File) : RenderState
    data class Failed(val clip: String, val message: String) : RenderState
}

/** App-wide render job so it survives screen changes inside the app. */
object RenderManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<RenderState>(RenderState.Idle)
    val state: StateFlow<RenderState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(ctx: Context, clip: String, src: File, dst: File, params: RenderParams) {
        val appCtx = ctx.applicationContext
        if (_state.value is RenderState.Running) return
        _state.value = RenderState.Running(clip, 0f, -1)
        job = scope.launch {
            val gl = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val tmp = File(dst.parentFile, dst.nameWithoutExtension + ".part.mp4")
            try {
                val startNs = System.nanoTime()
                withContext(gl) {
                    val j = coroutineContext[Job]
                    VideoRenderer.render(
                        ctx = appCtx, src = src, dst = tmp, params = params,
                        ensureActive = { j?.ensureActive() },
                        onProgress = { done, total ->
                            val elapsed = (System.nanoTime() - startNs) / 1e9f
                            val eta = if (done > 0) (elapsed / done * (total - done)).toInt() else -1
                            _state.value = RenderState.Running(clip, done / total.toFloat(), eta)
                        }
                    )
                }
                if (dst.exists()) dst.delete()
                check(tmp.renameTo(dst)) { "Could not save the rendered clip" }
                _state.value = RenderState.Done(clip, dst)
            } catch (e: CancellationException) {
                tmp.delete()
                _state.value = RenderState.Idle
                throw e
            } catch (t: Throwable) {
                tmp.delete()
                _state.value = RenderState.Failed(clip, t.message ?: "Render failed")
            } finally {
                gl.close()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun reset() {
        if (_state.value !is RenderState.Running) _state.value = RenderState.Idle
    }
}
