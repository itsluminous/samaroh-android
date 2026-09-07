package com.itsluminous.samaroh.feature.inventory.image

import android.content.Context
import android.util.Log
import com.itsluminous.samaroh.core.data.image.driveItemImageCacheFile
import com.itsluminous.samaroh.core.data.image.itemImageDir
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.drive.DriveService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a Drive-hosted item photo into the shared cache convention
 * (`inventory-images/drive-{id}.webp`, ADR-063) — the exact ADR-052/059 attachment
 * ladder, minus the Room stamp (the cache file IS the state):
 *
 * 1. Google linked → own-token `files.get?alt=media` (the uploader's own photos);
 * 2. own-token failed OR not linked → PUBLIC LINK download (`uc?export=download`, no
 *    credentials) — item photos are shared anyone-with-link at upload/repair, so a
 *    member whose token cannot read another account's file still gets the bytes. An
 *    HTML answer is rejected upstream ([DriveService.downloadPublicFile]) so an
 *    interstitial page is never cached as a photo;
 * 3. both failed (typically offline, or the permission repair has not reached the file
 *    yet) → null; the caller renders the placeholder and the next render/sync retries.
 *
 * Downloads write to a temp file first and rename into place, so a torn download never
 * becomes a cache hit. Concurrent requests for the same id share one download.
 */
@Singleton
class ItemPhotoDriveFetcher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val driveService: DriveService,
        private val googleAccountLinker: GoogleAccountLinker,
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
            val linked = googleAccountLinker.linkState.first() is GoogleLinkState.Linked
            if (linked) {
                val own = runCatching { driveService.downloadFile(driveImageId, temp) }
                if (own.isSuccess && temp.length() > 0L) return commit(temp, target, driveImageId)
                Log.i(TAG, "own-token photo download failed (drive=$driveImageId) — trying public link")
            }
            // ADR-059/063 member fallback: anyone-with-link files download without credentials.
            val public = runCatching { driveService.downloadPublicFile(driveImageId, temp) }
            if (public.isSuccess && temp.length() > 0L) return commit(temp, target, driveImageId)
            Log.w(TAG, "item photo download failed (drive=$driveImageId)", public.exceptionOrNull())
            temp.delete()
            return null
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
