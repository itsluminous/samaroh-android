package com.itsluminous.samaroh.core.designsystem.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A decoded, upright image plus what the decode had to do to get there — callers use
 * [rotationDegrees]/[wasDownsized] to decide whether the original bytes can be kept
 * verbatim (no rotation, no resize) instead of re-encoding.
 */
data class UprightImage(
    /** Pixels rotated upright per EXIF; dimensions are post-rotation. */
    val bitmap: Bitmap,
    /** EXIF rotation that was applied (0 when none/unsupported format). */
    val rotationDegrees: Int,
    /** Whether the source exceeded the requested bound and was scaled down. */
    val wasDownsized: Boolean,
)

/**
 * Decodes [uri] into an upright bitmap bounded to [maxDimensionPx] on the longest side:
 * bounds-only pass first, power-of-two subsample decode second (memory stays O(bound),
 * never O(source)), then EXIF rotation so every consumer — the interactive cropper AND
 * the compressors — sees the image the way the user shot it (ADR-050: the attachment
 * path previously skipped rotation). Null when the content is unreadable.
 */
suspend fun decodeUprightImage(
    context: Context,
    uri: Uri,
    maxDimensionPx: Int,
): UprightImage? = withContext(Dispatchers.IO) { decodeUprightImageBlocking(context, uri, maxDimensionPx) }

/**
 * Blocking core of [decodeUprightImage] for callers that already sit on their own
 * (injectable) IO dispatcher — hard-coding `Dispatchers.IO` here would silently escape
 * a test dispatcher.
 */
fun decodeUprightImageBlocking(
    context: Context,
    uri: Uri,
    maxDimensionPx: Int,
): UprightImage? =
    runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeStream returns null BY DESIGN with inJustDecodeBounds — only the
        // stream-open result decides success here; bounds carry the outcome.
        val boundsStream = resolver.openInputStream(uri) ?: return@runCatching null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = ImageCompression.sampleSizeFor(longest, maxDimensionPx)
            }
        val decoded =
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@runCatching null
        // EXIF parsing is best-effort: formats without EXIF support (some PNGs/BMPs)
        // must still decode — they just skip rotation.
        val rotation =
            runCatching {
                resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            }.getOrDefault(0)
        val upright =
            if (rotation == 0) {
                decoded
            } else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap
                    .createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    .also { if (it !== decoded) decoded.recycle() }
            }
        UprightImage(
            bitmap = upright,
            rotationDegrees = rotation,
            wasDownsized = longest > maxDimensionPx,
        )
    }.getOrNull()
