package com.itsluminous.samaroh.core.sync.engine

import com.itsluminous.samaroh.core.data.image.INVENTORY_IMAGES_BUCKET
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.storage.storage
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.File
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors a locally stored item photo to remote storage during a sync run (ADR-023) — the
 * "photos stored locally, mirroring deferred" gap Wave 1 left open. Called by the engine
 * BEFORE a `master_items` upsert is pushed, so a device-local file path never reaches the
 * server: the web app only ever sees Storage object paths.
 */
interface ItemImageMirror {
    sealed interface Result {
        /** Uploaded; the row must now carry [storagePath] as its `image_path`. */
        data class Uploaded(
            val storagePath: String,
        ) : Result

        /** The referenced local file no longer exists — push the row without a photo. */
        data object MissingFile : Result

        /** Transient failure (offline, timeout) — keep the op queued and retry later. */
        data class Retriable(
            val message: String,
        ) : Result

        /** The server refused the upload (RLS, bad request) — per-item error state. */
        data class Rejected(
            val message: String,
        ) : Result
    }

    suspend fun mirror(
        businessId: String,
        itemId: String,
        localPath: String,
    ): Result
}

/**
 * Supabase Storage implementation: uploads to the private `inventory-images` bucket at
 * `{business_id}/{item_id}/{millis}.webp`. The timestamped object name means a replaced
 * photo gets a NEW path — image caches (Coil here, browser/CDN on web) never serve a
 * stale photo. Rides the shared authed [SupabaseClient], so RLS evaluates as the user.
 */
@Singleton
class StorageItemImageMirror
    @Inject
    constructor(
        private val client: SupabaseClient?,
        private val clock: Clock,
    ) : ItemImageMirror {
        override suspend fun mirror(
            businessId: String,
            itemId: String,
            localPath: String,
        ): ItemImageMirror.Result {
            // Unconfigured Supabase never reaches here (sync no-ops), but stay safe.
            val supabase = client ?: return ItemImageMirror.Result.Retriable("storage-unconfigured")
            val file = File(localPath)
            if (!file.isFile) return ItemImageMirror.Result.MissingFile
            val objectPath = "$businessId/$itemId/${clock.millis()}.webp"
            return try {
                supabase.storage.from(INVENTORY_IMAGES_BUCKET).upload(objectPath, file.readBytes()) { upsert = true }
                ItemImageMirror.Result.Uploaded(objectPath)
            } catch (e: RestException) {
                ItemImageMirror.Result.Rejected(e.message ?: "image-upload-rejected")
            } catch (e: HttpRequestTimeoutException) {
                ItemImageMirror.Result.Retriable(e.message ?: "image-upload-timeout")
            } catch (e: HttpRequestException) {
                ItemImageMirror.Result.Retriable(e.message ?: "image-upload-network")
            } catch (e: IOException) {
                ItemImageMirror.Result.Retriable(e.message ?: "image-upload-io")
            }
        }
    }
