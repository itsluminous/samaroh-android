package com.itsluminous.samaroh.feature.inventory.image

import android.content.Context
import android.graphics.Bitmap
import com.itsluminous.samaroh.core.designsystem.imaging.CompressionSpec
import com.itsluminous.samaroh.core.designsystem.imaging.ImageCompression
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Longest allowed side of a stored item photo (spec §1.1/§4.3: ≤320px WebP). */
const val ITEM_IMAGE_MAX_DIMENSION_PX = 320

/** Prepares and stores item photos locally; the sync engine mirrors them upstream later. */
interface ItemImageStore {
    /**
     * Stores the user-cropped [source] (already square from the interactive cropper;
     * center-square is applied defensively otherwise), scaled to at most
     * [ITEM_IMAGE_MAX_DIMENSION_PX], as a WebP file keyed by [itemId].
     * Returns the absolute file path, or null when the image cannot be written.
     */
    suspend fun compressItemImage(
        source: Bitmap,
        itemId: String,
    ): String?

    /** Deletes the stored photo of [itemId], if any — removal must not leak files on disk. */
    suspend fun deleteItemImage(itemId: String)
}

@Singleton
class LocalItemImageStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ItemImageStore {
        override suspend fun compressItemImage(
            source: Bitmap,
            itemId: String,
        ): String? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val side = minOf(source.width, source.height)
                    val cropped =
                        if (source.width == source.height) {
                            source
                        } else {
                            Bitmap.createBitmap(
                                source,
                                (source.width - side) / 2,
                                (source.height - side) / 2,
                                side,
                                side,
                            )
                        }
                    val dir = File(context.filesDir, "inventory-images").apply { mkdirs() }
                    val file = File(dir, "$itemId.webp")
                    // Shared pipeline at the ITEM level (~50% compression, ADR-050):
                    // thumbnails render small, so heavy squeeze at the existing ≤320px.
                    ImageCompression.encodeToFile(cropped, CompressionSpec.ItemPhoto, file)
                    if (cropped !== source) cropped.recycle()
                    file.absolutePath
                }.getOrNull()
            }

        override suspend fun deleteItemImage(itemId: String) {
            withContext(Dispatchers.IO) {
                File(File(context.filesDir, "inventory-images"), "$itemId.webp").delete()
            }
        }
    }
