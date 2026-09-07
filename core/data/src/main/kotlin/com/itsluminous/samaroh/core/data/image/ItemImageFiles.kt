package com.itsluminous.samaroh.core.data.image

import android.content.Context
import java.io.File

/*
 * The app-private directory holding device-local item photos (ADR-055) and, since
 * ADR-063, the Drive-download cache. ONE convention, shared by its writers
 * (`feature:inventory`'s `LocalItemImageStore` for originals, `ItemPhotoDriveFetcher`
 * for Drive caches) and its readers (the resolver here in `core:data`, the Drive
 * item-photo mirror in `core:google`). Keeping it here means the file-name contract can
 * never silently drift between modules.
 *
 * Two file kinds live side by side:
 * - `{itemId}.webp` — the ORIGINAL of a photo added on THIS device (the mirror's upload
 *   source; deleted by photo removal);
 * - `drive-{driveImageId}.webp` — a cached download of a Drive-hosted photo (ADR-063).
 *   The drive file id changes whenever a photo is replaced, so the cache never serves a
 *   stale image; orphaned entries are swept by the post-sync prefetcher.
 */

/** Directory under `filesDir` where item photos live before/after mirroring. */
fun itemImageDir(context: Context): File = File(context.filesDir, "inventory-images")

/** The device-local photo file of [itemId] — exists only for photos added on THIS device. */
fun localItemImageFile(
    context: Context,
    itemId: String,
): File = File(itemImageDir(context), "$itemId.webp")

private const val DRIVE_CACHE_PREFIX = "drive-"

/** The cached Drive download of [driveImageId] (ADR-063) — written by the fetcher. */
fun driveItemImageCacheFile(
    context: Context,
    driveImageId: String,
): File = File(itemImageDir(context), "$DRIVE_CACHE_PREFIX$driveImageId.webp")

/** True when [file] is a Drive-download cache entry — used by the orphan sweep. */
fun isDriveItemImageCacheFile(file: File): Boolean = file.name.startsWith(DRIVE_CACHE_PREFIX) && file.name.endsWith(".webp")

/** The drive file id a cache entry was stored under, or null for non-cache files. */
fun driveImageIdOfCacheFile(file: File): String? =
    file.name
        .takeIf { isDriveItemImageCacheFile(file) }
        ?.removePrefix(DRIVE_CACHE_PREFIX)
        ?.removeSuffix(".webp")
