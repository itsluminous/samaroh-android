package com.itsluminous.samaroh.core.data.image

/*
 * `master_items.image_path` is DEVICE-LOCAL ONLY (ADR-065): the absolute path of a photo
 * file added on THIS device (`/data/user/0/…/inventory-images/<id>.webp`). It never
 * syncs — the server column is dropped; `drive_image_id` is the cross-device source of
 * an item photo. (Legacy rows written before ADR-063 may still hold a relative
 * Supabase-Storage-era object path in the local DB; it simply fails the file-exists
 * check and the row renders via `drive_image_id` like any remote photo.)
 */

/** Where an item photo should be loaded from (resolved via [ItemImageResolver], ADR-063). */
sealed interface ItemImageSource {
    /** A photo present on this device's disk — the original or a Drive-download cache. */
    data class LocalFile(
        val path: String,
    ) : ItemImageSource

    /** A Drive-hosted photo not on this device yet — download and cache, then render. */
    data class DriveFile(
        val driveImageId: String,
    ) : ItemImageSource

    /** No local bytes and no Drive id — render the placeholder. */
    data object Unavailable : ItemImageSource
}

/**
 * Resolves an item photo for display (ADR-063 ladder): device-local file (original or
 * Drive cache) → Drive download by `drive_image_id` → placeholder. Pure file-system
 * checks — the Drive download itself is the caller's job (feature:inventory fetcher).
 */
interface ItemImageResolver {
    fun resolve(
        itemId: String,
        imagePath: String?,
        driveImageId: String?,
    ): ItemImageSource
}
