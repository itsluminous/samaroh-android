package com.itsluminous.samaroh.feature.inventory.image

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.itsluminous.samaroh.core.data.image.ItemImageResolver
import com.itsluminous.samaroh.core.data.image.ItemImageSource
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.InventoryRepository
import com.itsluminous.samaroh.core.data.sync.PostSyncHook
import com.itsluminous.samaroh.core.model.MasterItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline warm-up for Storage-hosted item photos (ADR-062): after every completed sync
 * pull, download each item photo that is not yet in Coil's disk cache, keyed by its
 * stable object path (ADR-023). The inventory screens then render EVERY item photo fully
 * offline — including photos added on the web that this device never scrolled on-screen
 * while online — and a photo evicted from the size-bounded cache is re-warmed on the
 * next sync. Deliberately NOT the local-file convention ([localItemImageFile]): that
 * directory means "added on THIS device" to the ADR-055 Drive mirror, and a mirrored
 * download would masquerade as a local original. Expense-bill attachments are untouched.
 *
 * Cheap and idempotent: photos already in the disk cache are skipped via a cache probe
 * (no request, no decode); only misses hit the network, and failures (offline mid-sync,
 * expired token) are silently dropped — the next sync retries.
 */
@Singleton
class ItemImagePrefetcher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val businessRepository: BusinessRepository,
        private val inventoryRepository: InventoryRepository,
        private val resolver: ItemImageResolver,
    ) : PostSyncHook {
        @OptIn(ExperimentalCoilApi::class)
        override suspend fun onSyncApplied() {
            val loader = context.imageLoader
            val diskCache = loader.diskCache
            val businesses = businessRepository.businesses().first().filter { it.deletedAt == null }
            for (business in businesses) {
                val items = inventoryRepository.masterItems(business.id).first()
                for (source in prefetchSources(items, resolver)) {
                    val alreadyCached =
                        diskCache
                            ?.openSnapshot(source.cacheKey)
                            ?.use { true } ?: false
                    if (alreadyCached) continue
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(source.url)
                            // Same stable keys as the display path (ADR-023) — the warmed
                            // entry IS the one the screens read.
                            .memoryCacheKey(source.cacheKey)
                            .diskCacheKey(source.cacheKey)
                            // Warm the DISK cache only; screens fill the memory cache on render.
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .apply { source.accessToken?.let { addHeader("Authorization", "Bearer $it") } }
                            .build()
                    loader.enqueue(request)
                }
            }
        }
    }

/**
 * The photos worth warming: items whose `image_path` resolves to a Storage object.
 * Local files need no cache and `Unavailable` (Supabase unconfigured) cannot be fetched.
 * Pure — unit-tested selection logic.
 */
internal fun prefetchSources(
    items: List<MasterItem>,
    resolver: ItemImageResolver,
): List<ItemImageSource.RemoteObject> =
    items
        .filter { it.deletedAt == null }
        .mapNotNull { it.imagePath }
        .mapNotNull { resolver.resolve(it) as? ItemImageSource.RemoteObject }
