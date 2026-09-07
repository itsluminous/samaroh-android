package com.itsluminous.samaroh.feature.inventory

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.image.ItemImageResolver
import com.itsluminous.samaroh.core.data.image.ItemImageSource
import com.itsluminous.samaroh.feature.inventory.image.ItemPhoto
import com.itsluminous.samaroh.feature.inventory.image.resolveItemImageFile
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

/**
 * ADR-063: the rendered model of an item photo — local files resolve directly, Drive
 * sources go through the fetcher (and its failure renders the placeholder), and no
 * source at all short-circuits to null.
 */
class ItemImageModelTest {
    private fun resolverReturning(source: ItemImageSource) =
        object : ItemImageResolver {
            override fun resolve(
                itemId: String,
                imagePath: String?,
                driveImageId: String?,
            ): ItemImageSource = source
        }

    private val photo = ItemPhoto(itemId = "i-1", imagePath = "/data/user/0/app/files/inventory-images/i-1.webp", driveImageId = "d-1")

    @Test
    fun `local file source maps to that file`() =
        runTest {
            val resolver = resolverReturning(ItemImageSource.LocalFile("/data/user/0/app/files/inventory-images/i-1.webp"))

            val model = resolveItemImageFile(photo, resolver) { error("no fetch expected") }

            assertThat(model).isEqualTo(File("/data/user/0/app/files/inventory-images/i-1.webp"))
        }

    @Test
    fun `drive source is fetched and maps to the cached file`() =
        runTest {
            val resolver = resolverReturning(ItemImageSource.DriveFile("d-1"))
            val cached = File("/cache/drive-d-1.webp")

            val model = resolveItemImageFile(photo, resolver) { id -> cached.takeIf { id == "d-1" } }

            assertThat(model).isEqualTo(cached)
        }

    @Test
    fun `a failed drive fetch maps to no model (placeholder)`() =
        runTest {
            val resolver = resolverReturning(ItemImageSource.DriveFile("d-1"))

            val model = resolveItemImageFile(photo, resolver) { null }

            assertThat(model).isNull()
        }

    @Test
    fun `unavailable source maps to no model`() =
        runTest {
            val resolver = resolverReturning(ItemImageSource.Unavailable)

            assertThat(resolveItemImageFile(photo, resolver) { error("no fetch expected") }).isNull()
        }

    @Test
    fun `a photo with no sources at all needs no resolution`() {
        assertThat(ItemPhoto(itemId = "i-1", imagePath = null, driveImageId = null).hasAnySource).isFalse()
        assertThat(photo.hasAnySource).isTrue()
    }
}
