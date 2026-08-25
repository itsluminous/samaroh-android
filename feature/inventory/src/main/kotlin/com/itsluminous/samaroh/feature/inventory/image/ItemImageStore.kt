package com.itsluminous.samaroh.feature.inventory.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Longest allowed side of a stored item photo (spec §1.1/§4.3: ≤320px WebP). */
const val ITEM_IMAGE_MAX_DIMENSION_PX = 320

private const val WEBP_QUALITY = 80

/** Prepares and stores item photos locally; the sync engine mirrors them upstream later. */
interface ItemImageStore {
    /**
     * Crops [source] to a centered square, scales it to at most
     * [ITEM_IMAGE_MAX_DIMENSION_PX] and stores it as a WebP file keyed by [itemId].
     * Returns the absolute file path, or null when the image cannot be read.
     */
    suspend fun compressItemImage(
        source: Uri,
        itemId: String,
    ): String?
}

@Singleton
class LocalItemImageStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ItemImageStore {
        override suspend fun compressItemImage(
            source: Uri,
            itemId: String,
        ): String? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val decoded = decodeDownsampled(source) ?: return@runCatching null
                    val side = minOf(decoded.width, decoded.height)
                    val cropped =
                        Bitmap.createBitmap(
                            decoded,
                            (decoded.width - side) / 2,
                            (decoded.height - side) / 2,
                            side,
                            side,
                        )
                    val scaled =
                        if (side > ITEM_IMAGE_MAX_DIMENSION_PX) {
                            Bitmap.createScaledBitmap(cropped, ITEM_IMAGE_MAX_DIMENSION_PX, ITEM_IMAGE_MAX_DIMENSION_PX, true)
                        } else {
                            cropped
                        }
                    val dir = File(context.filesDir, "inventory-images").apply { mkdirs() }
                    val file = File(dir, "$itemId.webp")
                    file.outputStream().use { out -> scaled.compress(webpFormat(), WEBP_QUALITY, out) }
                    file.absolutePath
                }.getOrNull()
            }

        private fun webpFormat(): Bitmap.CompressFormat =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

        /** Two-pass decode: bounds first, then a power-of-two downsample near the target size. */
        private fun decodeDownsampled(source: Uri): Bitmap? {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= ITEM_IMAGE_MAX_DIMENSION_PX) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            return resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    }
