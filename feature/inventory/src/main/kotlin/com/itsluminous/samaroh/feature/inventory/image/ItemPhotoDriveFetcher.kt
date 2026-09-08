package com.itsluminous.samaroh.feature.inventory.image

import android.content.Context
import android.util.Log
import com.itsluminous.samaroh.core.data.image.driveItemImageCacheFile
import com.itsluminous.samaroh.core.data.image.itemImageDir
import com.itsluminous.samaroh.core.google.drive.DriveFetchResult
import com.itsluminous.samaroh.core.google.drive.DriveFileFetcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a Drive-hosted item photo into the shared cache convention
 * (`inventory-images/drive-{id}.webp`, ADR-063) via the shared [DriveFileFetcher]
 * ladder (own-token → anyone-with-link public download; ADR-052/059/063), minus the
 * Room stamp the attachment resolver does — the cache file IS the state. Both-rungs
 * failure (typically offline, or the permission repair has not reached the file yet)
 * → null; the caller renders the placeholder and the next render/sync retries.
 *
 * Downloads write to a temp file first and rename into place, so a torn download never
 * becomes a cache hit. Concurrent requests for the same id share one download.
 */
@Singleton
class ItemPhotoDriveFetcher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val driveFileFetcher: DriveFileFetcher,
    ) {
        private val inflight = mutableMapOf<String, Mutex>()
        private val inflightLock = Mutex()
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        /** Returns the cached file of [driveImageId], downloading it first if needed. */
        suspend fun fetch(driveImageId: String): File? {
            val target = driveItemImageCacheFile(context, driveImageId)
            if (target.isFile) return target
            val mutex = inflightLock.withLock { inflight.getOrPut(driveImageId) { Mutex() } }
            return try {
                mutex.withLock {
                    if (target.isFile) return@withLock target
                    withContext(ioDispatcher) { download(driveImageId, target) }
                }
            } finally {
                inflightLock.withLock { inflight.remove(driveImageId) }
            }
        }

        private suspend fun download(
            driveImageId: String,
            target: File,
        ): File? {
            itemImageDir(context).mkdirs()
            val temp = File(target.parentFile, "${target.name}.part")
            return when (driveFileFetcher.fetchInto(driveImageId, temp)) {
                is DriveFetchResult.Success -> commit(temp, target, driveImageId)
                is DriveFetchResult.Failure -> {
                    Log.w(TAG, "item photo download failed (drive=$driveImageId)")
                    null
                }
            }
        }

        private fun commit(
            temp: File,
            target: File,
            driveImageId: String,
        ): File? {
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            Log.i(TAG, "item photo cached from Drive (drive=$driveImageId, bytes=${target.length()})")
            return target
        }

        companion object {
            const val TAG = "SamarohItemImage"
        }
    }
