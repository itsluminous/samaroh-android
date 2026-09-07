package com.itsluminous.samaroh.feature.inventory.image

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.image.ItemImageResolver
import com.itsluminous.samaroh.core.data.image.ItemImageSource
import com.itsluminous.samaroh.core.data.image.isLocalItemImagePath
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test

/**
 * Selection logic of the offline item-photo warm-up (ADR-062): only live items with a
 * Storage-hosted photo are prefetched, keyed by the stable object path (ADR-023) so the
 * warmed disk-cache entry is exactly the one the display path reads.
 */
class ItemImagePrefetcherTest {
    private val resolver =
        object : ItemImageResolver {
            override fun resolve(imagePath: String): ItemImageSource =
                if (isLocalItemImagePath(imagePath)) {
                    ItemImageSource.LocalFile(imagePath)
                } else {
                    ItemImageSource.RemoteObject(
                        url = "https://storage.example/object/authenticated/inventory-images/$imagePath",
                        accessToken = "token",
                        cacheKey = imagePath,
                    )
                }
        }

    @Test
    fun `selects only storage-hosted photos of live items, keyed by object path`() {
        val items =
            listOf(
                Fixtures.masterItem(id = "i-remote", imagePath = "biz-1/i-remote/photo.webp"),
                Fixtures.masterItem(id = "i-local", imagePath = "/data/user/0/app/files/inventory-images/i-local.webp"),
                Fixtures.masterItem(id = "i-none", imagePath = null),
                Fixtures.masterItem(id = "i-deleted", imagePath = "biz-1/i-deleted/photo.webp", deletedAt = Fixtures.NOW),
            )

        val sources = prefetchSources(items, resolver)

        assertThat(sources.map { it.cacheKey }).containsExactly("biz-1/i-remote/photo.webp")
    }

    @Test
    fun `unconfigured resolver yields nothing to prefetch`() {
        val unavailable =
            object : ItemImageResolver {
                override fun resolve(imagePath: String): ItemImageSource = ItemImageSource.Unavailable
            }
        val items = listOf(Fixtures.masterItem(id = "i-1", imagePath = "biz-1/i-1/photo.webp"))

        assertThat(prefetchSources(items, unavailable)).isEmpty()
    }
}
