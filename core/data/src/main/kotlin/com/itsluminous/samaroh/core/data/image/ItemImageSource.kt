package com.itsluminous.samaroh.core.data.image

/** Supabase Storage bucket holding item photos — shared with the web app. */
const val INVENTORY_IMAGES_BUCKET = "inventory-images"

/**
 * `master_items.image_path` carries either of two forms (ADR-023):
 * - an absolute local file path (`/data/user/0/…/inventory-images/<id>.webp`) for a photo
 *   added on this device that has not been mirrored to Supabase Storage yet, or
 * - a Storage object path (`<business_id>/<item_id>/<file>.webp`, no leading slash) once
 *   mirrored — the form the web app writes for imported/web-added photos.
 */
fun isLocalItemImagePath(imagePath: String): Boolean =
    imagePath.startsWith("/") ||
        imagePath.startsWith("file:") ||
        imagePath.startsWith("content:")

/** Where an item photo should be loaded from (resolved via [ItemImageResolver]). */
sealed interface ItemImageSource {
    /** A photo present on this device's disk. */
    data class LocalFile(
        val path: String,
    ) : ItemImageSource

    /** A photo in the private Supabase Storage bucket, fetched with the user's token. */
    data class RemoteObject(
        /** Authenticated Storage object URL (stable per object — safe as a cache key). */
        val url: String,
        /** Current access token, or null when signed out — cached images still render. */
        val accessToken: String?,
        /** Stable Coil memory/disk cache key (the raw `image_path` value). */
        val cacheKey: String,
    ) : ItemImageSource

    /** A remote-only photo that cannot be fetched (Supabase not configured). */
    data object Unavailable : ItemImageSource
}

/** Maps a raw `image_path` value to its displayable [ItemImageSource]. */
interface ItemImageResolver {
    fun resolve(imagePath: String): ItemImageSource
}
