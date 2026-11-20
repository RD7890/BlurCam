package com.blurcam.app.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawDepth(val data: FloatArray, val h: Int, val w: Int)

/** Depth Anything V2 Small (ONNX, dynamic H/W) running on ONNX Runtime. Output = relative inverse depth (larger = nearer). */
object DepthModel {
    private const val ASSET = "models/depth_anything_v2_vits_dynamic.onnx"
    private const val FILE = "depth_anything_v2_vits_dynamic.onnx"

    private var session: OrtSession? = null
    private var inputName: String = ""

    @Synchronized
    private fun session(ctx: Context): OrtSession {
        session?.let { return it }
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        val model = File(dir, FILE)
        if (!model.exists() || model.length() == 0L) {
            val tmp = File(dir, "$FILE.part")
            try {
                ctx.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            } catch (t: Throwable) {
                tmp.delete()
                throw IllegalStateException("Depth model is missing from the app (assets/$ASSET). Rebuild with the model downloaded.", t)
            }
            check(tmp.renameTo(model)) { "Could not install the depth model" }
        }
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val s = env.createSession(model.absolutePath, opts)
        inputName = s.inputNames.first()
        Log.i("BlurCam", "Depth model ready, input=$inputName")
        session = s
        return s
    }

    /** [chw] is 1x3xHxW float, ImageNet-normalised. */
    fun infer(ctx: Context, chw: FloatArray, h: Int, w: Int): RawDepth {
        val s = session(ctx)
        val env = OrtEnvironment.getEnvironment()
        val bb = ByteBuffer.allocateDirect(chw.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(chw)
        fb.rewind()
        OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, h.toLong(), w.toLong())).use { input ->
            s.run(mapOf(inputName to input)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape
                val oh = shape[shape.size - 2].toInt()
                val ow = shape[shape.size - 1].toInt()
                val arr = FloatArray(oh * ow)
                out.floatBuffer.get(arr)
                return RawDepth(arr, oh, ow)
            }
        }
    }
}
