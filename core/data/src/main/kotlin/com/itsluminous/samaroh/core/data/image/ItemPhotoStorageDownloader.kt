package com.itsluminous.samaroh.core.data.image

/**
 * Downloads an item photo's bytes from the Supabase Storage `inventory-images` bucket
 * (ADR-058, extending ADR-055): the Drive mirror needs the ORIGINAL bytes for rows whose
 * photo only exists in Storage — web-imported items and legacy photos with no device-local
 * file. Implemented in `core:auth` over the shared authed client (RLS evaluates as the
 * signed-in user, same as [ItemImageResolver] display fetches); consumed by
 * `core:google`'s Drive item-photo mirror.
 *
 * Failures (Supabase unconfigured, offline, RLS denial, missing object) surface as a
 * normal [Result.failure] — the mirror leaves the row pending and retries next sync run.
 */
fun interface ItemPhotoStorageDownloader {
    /** Fetches the object at [objectPath] (a Storage object path, ADR-023 form). */
    suspend fun download(objectPath: String): Result<ByteArray>
}
