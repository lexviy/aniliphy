package com.aniliphy.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Pictures of what's playing: a frame to share or keep, and the small one Up next and the widget show. */
internal object FrameGrab {
    private const val DIR = "frames"

    /** The frame on screen, [width] px wide (the video's own size when null), with the subtitles on it when shown. */
    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun copy(view: PlayerView?, width: Int? = null, subtitles: Boolean = false): Bitmap? {
        val surface = view?.videoSurfaceView as? SurfaceView ?: return null
        if (surface.width <= 0 || surface.height <= 0 || !surface.holder.surface.isValid) return null
        val w = width ?: surface.width
        val h = (surface.height.toLong() * w / surface.width).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { done ->
            try {
                PixelCopy.request(surface, bitmap, { result -> if (done.isActive) done.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            } catch (_: IllegalArgumentException) {
                done.resume(false)
            }
        }
        if (!ok) return null
        if (subtitles) view.subtitleView?.takeIf { it.isShown }?.let { subs ->
            // The subtitles sit over the whole player; drawn on the frame at the same place.
            val surfaceAt = IntArray(2).also(surface::getLocationInWindow)
            val subsAt = IntArray(2).also(subs::getLocationInWindow)
            val scale = w / surface.width.toFloat()
            Canvas(bitmap).apply {
                scale(scale, scale)
                translate((subsAt[0] - surfaceAt[0]).toFloat(), (subsAt[1] - surfaceAt[1]).toFloat())
                subs.draw(this)
            }
        }
        return bitmap
    }

    /** Keeps where you are in [mediaId] as a cover-shaped picture for Up next, the widget and the notification. */
    suspend fun keepPlace(context: Context, mediaId: Int, frame: Bitmap) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        // Cut to the shape of a cover, around the middle, where the action usually is.
        val targetW = (frame.height * 0.7f).toInt().coerceAtMost(frame.width)
        val cut = Bitmap.createBitmap(frame, (frame.width - targetW) / 2, 0, targetW, frame.height)
        // A new name each time, so the widget sees the picture changed.
        val file = File(dir, "$mediaId-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { cut.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        dir.listFiles()?.filter { it.name.startsWith("$mediaId-") && it != file }?.forEach { it.delete() }
    }

    /** The newest picture kept for [mediaId], as a file path, when there is one. */
    fun place(context: Context, mediaId: String): String? =
        File(context.filesDir, DIR).listFiles()?.filter { it.name.startsWith("$mediaId-") }?.maxByOrNull { it.lastModified() }?.absolutePath

    fun forget(context: Context) {
        File(context.filesDir, DIR).deleteRecursively()
    }

    fun share(context: Context, frame: Bitmap, text: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "aniliphy-frame-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        chooser.clipData = android.content.ClipData.newRawUri(null, uri)
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }

    /** Saves to Pictures/aniliphy; false where Android is too old to do it without asking for storage. */
    suspend fun saveToGallery(context: Context, frame: Bitmap, name: String): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/aniliphy")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { frame.compress(Bitmap.CompressFormat.JPEG, 95, it) } == true
        }.getOrDefault(false)
        if (!written) {
            resolver.delete(uri, null, null)
            return@withContext false
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    }
}
