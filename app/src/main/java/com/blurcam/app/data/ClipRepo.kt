package com.blurcam.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.LruCache
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Clip(
    val raw: File,
    val rendered: File?,
    val durationMs: Long,
    val createdMs: Long,
    val number: Int,
) {
    val id: String get() = raw.nameWithoutExtension
    val title: String get() = "Take %02d".format(Locale.US, number)
    val subtitle: String
        get() = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(createdMs))
}

object ClipRepo {

    private fun rawDir(ctx: Context) = File(ctx.filesDir, "raw").apply { mkdirs() }
    private fun renderedDir(ctx: Context) = File(ctx.filesDir, "rendered").apply { mkdirs() }

    fun newRawFile(ctx: Context) = File(rawDir(ctx), "take_${System.currentTimeMillis()}.mp4")

    fun renderedFile(ctx: Context, raw: File) = File(renderedDir(ctx), raw.name)

    /** Blocking. Call from a background dispatcher. */
    fun list(ctx: Context): List<Clip> {
        val files = rawDir(ctx).listFiles()
            ?.filter { it.isFile && it.extension == "mp4" && it.length() > 0 }
            ?.sortedBy { it.name } ?: return emptyList()
        return files.mapIndexed { idx, f ->
            val created = f.nameWithoutExtension.removePrefix("take_").toLongOrNull() ?: f.lastModified()
            val out = renderedFile(ctx, f).takeIf { it.exists() && it.length() > 0 }
            Clip(f, out, durationOf(f), created, idx + 1)
        }.reversed()
    }

    fun durationOf(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    fun delete(ctx: Context, raw: File) {
        raw.delete()
        renderedFile(ctx, raw).delete()
        thumbs.remove(raw.path)
    }

    private val thumbs = LruCache<String, Bitmap>(24)

    /** Blocking. Small poster frame for the library grid. */
    fun thumbnail(file: File): Bitmap? {
        thumbs.get(file.path)?.let { return it }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val f = r.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0) ?: return null
            val k = 420f / minOf(f.width, f.height)
            val out = if (k < 1f) Bitmap.createScaledBitmap(f, (f.width * k).toInt().coerceAtLeast(1), (f.height * k).toInt().coerceAtLeast(1), true) else f
            if (out !== f) f.recycle()
            thumbs.put(file.path, out)
            out
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Copies the clip into Movies/BlurCam so it shows up in the gallery. */
    fun saveToGallery(ctx: Context, file: File): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "BlurCam_${file.nameWithoutExtension}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BlurCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share cinematic clip").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
