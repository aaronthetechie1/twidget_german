package com.tjg.twidget.brief

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.tjg.twidget.ui.ProfileImageLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal data class BriefCoachImage(val id: String, val bitmap: Bitmap)

internal object BriefCoachMedia {
    internal fun localPreview(context: Context, source: String, video: Boolean): Bitmap? = runCatching {
        val uri = Uri.parse(source)
        if (uri.scheme != "content") null else if (video) videoFrame(context, uri) else localImage(context, uri)
    }.getOrNull()
    /** Own bounded copies, never recycle the shared UI image cache. No persistent AI-media cache. */
    suspend fun load(context: Context, input: BriefCoachInput): List<BriefCoachImage> {
        val images = mutableListOf<BriefCoachImage>()
        try {
            input.media.take(4).forEachIndexed { index, ref ->
                coroutineContext.ensureActive()
                var owned: Bitmap? = null
                val bitmap = try { runInterruptible(Dispatchers.IO) {
                    owned = if (!ref.local) {
                        val uri = Uri.parse(ref.source)
                        if (uri.scheme == "https" && !uri.host.isNullOrBlank()) ProfileImageLoader.coachingBitmap(context, ref.source) else null
                    } else if (Uri.parse(ref.source).scheme == "content") {
                        if (ref.video) videoFrame(context, Uri.parse(ref.source)) else localImage(context, Uri.parse(ref.source))
                    } else null
                    owned
                }
                } catch (error: Exception) {
                    if (error is CancellationException) { owned?.recycle(); throw error }
                    null
                }
                if (bitmap != null) images += BriefCoachImage("m$index", bitmap)
            }
            return images
        } catch (error: Throwable) {
            images.forEach { it.bitmap.recycle() }
            throw error
        }
    }

    private fun localImage(context: Context, uri: Uri): Bitmap? {
        val data = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var size = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= 20 * 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(data))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun videoFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
                if (width <= 0 || height <= 0) return null
                val scale = minOf(1.0, 1024.0 / maxOf(width, height))
                return retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
            }
            return null
        } finally {
            retriever.release()
        }
    }
}
