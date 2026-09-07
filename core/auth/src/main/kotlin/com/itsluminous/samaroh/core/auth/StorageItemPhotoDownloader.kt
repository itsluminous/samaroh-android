package com.itsluminous.samaroh.core.auth

import com.itsluminous.samaroh.core.data.image.INVENTORY_IMAGES_BUCKET
import com.itsluminous.samaroh.core.data.image.ItemPhotoStorageDownloader
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ItemPhotoStorageDownloader] over the shared authed [SupabaseClient] (ADR-058): fetches
 * an item photo's bytes from the private `inventory-images` bucket so the Drive mirror can
 * durably copy photos that were never on this device (web imports, legacy rows). Rides the
 * user's session exactly like [StorageItemImageResolver]'s display URLs — RLS evaluates as
 * the signed-in user. Unconfigured Supabase and any transport/REST error surface as
 * [Result.failure]; the caller leaves the row pending.
 */
@Singleton
class StorageItemPhotoDownloader
    @Inject
    constructor(
        private val client: SupabaseClient?,
    ) : ItemPhotoStorageDownloader {
        override suspend fun download(objectPath: String): Result<ByteArray> {
            val supabase = client ?: return Result.failure(IllegalStateException("storage-unconfigured"))
            return runCatching { supabase.storage.from(INVENTORY_IMAGES_BUCKET).downloadAuthenticated(objectPath) }
        }
    }
