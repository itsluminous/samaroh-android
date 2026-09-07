package com.itsluminous.samaroh.core.data.image

/**
 * `master_items.image_path` carries either of two forms (ADR-023, semantics revised by
 * ADR-063):
 * - an absolute local file path (`/data/user/0/…/inventory-images/<id>.webp`) for a photo
 *   added on THIS device — since ADR-063 this form is also what syncs (it is meaningless
 *   on other devices, which serve the photo from `drive_image_id` instead), or
 * - a legacy Supabase Storage object path (`<business_id>/<item_id>/<file>.webp`, no
 *   leading slash) written before ADR-063. The bucket is gone; such rows render via
 *   `drive_image_id` (every migrated row carries one) or the device-local original.
 */
fun isLocalItemImagePath(imagePath: String): Boolean =
    imagePath.startsWith("/") ||
        imagePath.startsWith("file:") ||
        imagePath.startsWith("content:")

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
