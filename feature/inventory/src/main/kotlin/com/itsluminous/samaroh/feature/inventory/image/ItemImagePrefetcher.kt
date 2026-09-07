package com.itsluminous.samaroh.feature.inventory.image

import android.content.Context
import com.itsluminous.samaroh.core.data.image.ItemImageResolver
import com.itsluminous.samaroh.core.data.image.ItemImageSource
import com.itsluminous.samaroh.core.data.image.driveImageIdOfCacheFile
import com.itsluminous.samaroh.core.data.image.isDriveItemImageCacheFile
import com.itsluminous.samaroh.core.data.image.itemImageDir
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.InventoryRepository
import com.itsluminous.samaroh.core.data.sync.PostSyncHook
import com.itsluminous.samaroh.core.model.MasterItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline warm-up for Drive-hosted item photos (ADR-062, Drive-first since ADR-063):
 * after every completed sync pull, download every live item photo that resolves to a
 * not-yet-cached Drive file into the shared cache convention
 * (`inventory-images/drive-{id}.webp`). The inventory screens then render EVERY item
 * photo fully offline — including photos added on other devices this one never scrolled
 * on-screen while online. Photos are WebP ≤320px (ADR-025), so a full warm-up is a few
 * MB once; later syncs are file stats only.
 *
 * Also sweeps ORPHANED cache entries: a replaced photo gets a NEW drive file id (the
 * editor clears `drive_image_id`; the mirror mints a fresh Drive file), so the stale
 * `drive-{oldId}.webp` is no longer referenced by any live row and is deleted here —
 * the cache can never serve a stale image AND never grows without bound.
 *
 * Failures are dropped silently (offline mid-sync, permission repair not yet landed);
 * the next sync — or the next on-screen render — retries.
 */
@Singleton
class ItemImagePrefetcher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val businessRepository: BusinessRepository,
        private val inventoryRepository: InventoryRepository,
        private val resolver: ItemImageResolver,
        private val fetcher: ItemPhotoDriveFetcher,
    ) : PostSyncHook {
        override suspend fun onSyncApplied() {
            val businesses = businessRepository.businesses().first().filter { it.deletedAt == null }
            val liveDriveIds = mutableSetOf<String>()
            for (business in businesses) {
                val items = inventoryRepository.masterItems(business.id).first()
                items.forEach { item -> item.driveImageId?.takeIf { item.deletedAt == null }?.let(liveDriveIds::add) }
                for (driveImageId in prefetchDriveIds(items, resolver)) {
                    fetcher.fetch(driveImageId)
                }
            }
            sweepOrphanedCache(liveDriveIds)
        }

        /** Deletes `drive-*.webp` cache entries no live row references anymore. */
        private fun sweepOrphanedCache(liveDriveIds: Set<String>) {
            itemImageDir(context)
                .listFiles()
                .orEmpty()
                .filter { isDriveItemImageCacheFile(it) }
                .forEach { file ->
                    val id = driveImageIdOfCacheFile(file) ?: return@forEach
                    if (id !in liveDriveIds) file.delete()
                }
        }
    }

/**
 * The photos worth warming: live items whose ladder resolution lands on a Drive file
 * (nothing local on this device yet). Locally-resolved photos need no cache and
 * `Unavailable` cannot be fetched. Pure — unit-tested selection logic.
 */
internal fun prefetchDriveIds(
    items: List<MasterItem>,
    resolver: ItemImageResolver,
): List<String> =
    items
        .filter { it.deletedAt == null }
        .mapNotNull { item ->
            (resolver.resolve(item.id, item.imagePath, item.driveImageId) as? ItemImageSource.DriveFile)?.driveImageId
        }
