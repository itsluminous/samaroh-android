package com.itsluminous.samaroh.core.data.image

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ADR-063 resolution ladder for `master_items` photos, cheapest source first:
 *
 * 1. the row's own `image_path` when it is a LOCAL path whose file exists (a photo added
 *    on this device — the path syncs but only ever resolves here);
 * 2. the legacy device-local original `{itemId}.webp` (photos taken on this device whose
 *    `image_path` was rewritten to a Storage object path by the retired ADR-023 mirror);
 * 3. the Drive-download cache `drive-{driveImageId}.webp` (ADR-063 — written by the
 *    fetcher/prefetcher, so offline rendering keeps working, ADR-062 spirit);
 * 4. `drive_image_id` set but nothing cached → [ItemImageSource.DriveFile]: the caller
 *    downloads (own token → public link) and caches, then re-resolves;
 * 5. nothing → [ItemImageSource.Unavailable] (placeholder icon).
 *
 * Supabase Storage plays no part: the `inventory-images` bucket is gone (ADR-063).
 * Only cheap `File.isFile` stats — callers run resolution off the main thread anyway
 * because step 4 may follow with network I/O.
 */
@Singleton
class LocalFirstItemImageResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ItemImageResolver {
        override fun resolve(
            itemId: String,
            imagePath: String?,
            driveImageId: String?,
        ): ItemImageSource {
            if (imagePath != null && isLocalItemImagePath(imagePath)) {
                val file = File(imagePath)
                if (file.isFile) return ItemImageSource.LocalFile(file.absolutePath)
            }
            val original = localItemImageFile(context, itemId)
            if (original.isFile) return ItemImageSource.LocalFile(original.absolutePath)
            if (driveImageId != null) {
                val cached = driveItemImageCacheFile(context, driveImageId)
                if (cached.isFile) return ItemImageSource.LocalFile(cached.absolutePath)
                return ItemImageSource.DriveFile(driveImageId)
            }
            return ItemImageSource.Unavailable
        }
    }
