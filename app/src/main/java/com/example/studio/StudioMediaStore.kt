package com.example.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Persists user-picked Studio media (PNG, JPG, WebP, GIF, MP4/WebM) into app-private storage
 * so saved Image/GIF/Video layers NEVER lose URI permissions or go blank after app restarts.
 * Also detects real pixel dimensions & decodes safe memory-bounded bitmaps for Chroma Keying.
 */
object StudioMediaStore {

    private const val MEDIA_DIR_NAME = "studio_media"
    private const val MAX_COPY_BYTES = 120L * 1024L * 1024L // 120 MB safety guard

    data class PersistedMediaResult(
        val persistentUri: String,
        val displayName: String,
        val widthPx: Int,
        val heightPx: Int,
        val isGif: Boolean,
        val isVideo: Boolean
    )

    suspend fun importMediaToAppStorage(
        context: Context,
        sourceUri: Uri,
        isVideoHint: Boolean
    ): Result<PersistedMediaResult> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(sourceUri).orEmpty().lowercase()
            val isVideo = isVideoHint || mime.startsWith("video/")
            val isGif = !isVideo && (mime.contains("gif") || sourceUri.toString().endsWith(".gif", ignoreCase = true))

            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.isNotBlank() }
                ?: when {
                    isVideo -> "mp4"
                    isGif -> "gif"
                    mime.contains("png") -> "png"
                    mime.contains("webp") -> "webp"
                    else -> "jpg"
                }

            val dir = File(context.filesDir, MEDIA_DIR_NAME).apply { if (!exists()) mkdirs() }
            val outFile = File(dir, "media_${UUID.randomUUID().toString().take(12)}.$ext")

            resolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_COPY_BYTES) {
                            throw IllegalStateException("Selected media file exceeds 120 MB limit.")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: throw IllegalStateException("Unable to read selected media file.")

            val (w, h) = if (isVideo) {
                probeVideoDimensions(outFile)
            } else {
                probeImageDimensions(outFile)
            }

            val fallbackName = when {
                isVideo -> "Video Overlay"
                isGif -> "Animated GIF"
                else -> "Custom Image"
            }

            PersistedMediaResult(
                persistentUri = Uri.fromFile(outFile).toString(),
                displayName = fallbackName,
                widthPx = w,
                heightPx = h,
                isGif = isGif,
                isVideo = isVideo
            )
        }
    }

    fun probeImageDimensions(file: File): Pair<Int, Int> {
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            opts.outWidth.coerceAtLeast(0) to opts.outHeight.coerceAtLeast(0)
        }.getOrDefault(0 to 0)
    }

    fun probeVideoDimensions(file: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (_: Exception) {
            0 to 0
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Computes clean aspect-matched normalized width/height for a newly added source on the canvas.
     */
    fun computeNormalizedLayerSize(
        mediaWidthPx: Int,
        mediaHeightPx: Int,
        sceneIsVertical: Boolean
    ): Pair<Float, Float> {
        if (mediaWidthPx <= 0 || mediaHeightPx <= 0) {
            return 0.44f to 0.36f
        }
        val sceneAspect = if (sceneIsVertical) 9f / 16f else 16f / 9f
        val mediaAspect = mediaWidthPx.toFloat() / mediaHeightPx.toFloat()
        // Target max 52% width or 52% height while honoring media aspect ratio in canvas space
        val targetW = 0.48f
        val targetH = (targetW * sceneAspect / mediaAspect).coerceIn(0.14f, 0.72f)
        val adjustedW = (targetH * mediaAspect / sceneAspect).coerceIn(0.16f, 0.78f)
        return adjustedW to targetH
    }

    /**
     * Decodes a memory-safe ARGB_8888 Bitmap from a persisted file:// or content:// URI
     * for Chroma Key processing or Eyedropper color sampling.
     */
    fun decodeScaledBitmap(
        context: Context,
        uriString: String,
        isVideo: Boolean,
        maxDimensionPx: Int = 720
    ): Bitmap? {
        if (uriString.isBlank()) return null
        return runCatching {
            val uri = Uri.parse(uriString)
            if (isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                        retriever.setDataSource(uri.path)
                    } else {
                        retriever.setDataSource(context, uri)
                    }
                    val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: return@runCatching null
                    scaleBitmapIfNeeded(frame, maxDimensionPx)
                } finally {
                    runCatching { retriever.release() }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val rawW = bounds.outWidth
                val rawH = bounds.outHeight
                if (rawW <= 0 || rawH <= 0) return@runCatching null

                var sample = 1
                while (rawW / (sample * 2) >= maxDimensionPx && rawH / (sample * 2) >= maxDimensionPx) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                    ?: return@runCatching null
                scaleBitmapIfNeeded(bmp, maxDimensionPx)
            }
        }.getOrNull()
    }

    private fun openStream(context: Context, uri: Uri) = when (uri.scheme) {
        "file" -> uri.path?.let { File(it) }?.takeIf { it.exists() }?.inputStream()
        else -> context.contentResolver.openInputStream(uri)
    }

    private fun scaleBitmapIfNeeded(src: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= maxDim) {
            return if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
        }
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        val dstW = (src.width * ratio).toInt().coerceAtLeast(1)
        val dstH = (src.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, dstW, dstH, true)
        if (scaled != src) src.recycle()
        return scaled
    }

    /**
     * Deletes an app-private imported media file safely if it lives strictly inside studio_media/.
     */
    fun deleteIfAppManaged(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme != "file") return
            val path = uri.path ?: return
            val baseDir = File(context.filesDir, MEDIA_DIR_NAME).canonicalFile
            val target = File(path).canonicalFile
            if (target.path.startsWith(baseDir.path + File.separator) && target.exists()) {
                target.delete()
            }
        }
    }
}
