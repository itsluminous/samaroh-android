package com.itsluminous.samaroh.feature.inventory.image

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.image.ItemImageResolver
import com.itsluminous.samaroh.core.data.image.ItemImageSource
import com.itsluminous.samaroh.core.data.image.isLocalItemImagePath
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test

/**
 * Selection logic of the offline item-photo warm-up (ADR-062, Drive-first per ADR-063):
 * only live items whose ladder resolution lands on a not-yet-cached Drive file are
 * fetched; locally-resolved photos and source-less rows are skipped.
 */
class ItemImagePrefetcherTest {
    /** Ladder stand-in: local paths resolve locally; a drive id resolves to DriveFile. */
    private val resolver =
        object : ItemImageResolver {
            override fun resolve(
                itemId: String,
                imagePath: String?,
                driveImageId: String?,
            ): ItemImageSource =
                when {
                    imagePath != null && isLocalItemImagePath(imagePath) -> ItemImageSource.LocalFile(imagePath)
                    driveImageId != null -> ItemImageSource.DriveFile(driveImageId)
                    else -> ItemImageSource.Unavailable
                }
        }

    @Test
    fun `selects only live items that resolve to a drive file`() {
        val items =
            listOf(
                Fixtures.masterItem(id = "i-drive", imagePath = "biz-1/i-drive/photo.webp", driveImageId = "drive-1"),
                Fixtures.masterItem(id = "i-local", imagePath = "/data/user/0/app/files/inventory-images/i-local.webp"),
                Fixtures.masterItem(id = "i-none", imagePath = null),
                Fixtures.masterItem(id = "i-deleted", imagePath = null, driveImageId = "drive-dead", deletedAt = Fixtures.NOW),
            )

        assertThat(prefetchDriveIds(items, resolver)).containsExactly("drive-1")
    }

    @Test
    fun `an already-cached drive photo resolves locally and is not re-fetched`() {
        val cachedResolver =
            object : ItemImageResolver {
                override fun resolve(
                    itemId: String,
                    imagePath: String?,
                    driveImageId: String?,
                ): ItemImageSource = ItemImageSource.LocalFile("/data/user/0/app/files/inventory-images/drive-$driveImageId.webp")
            }
        val items = listOf(Fixtures.masterItem(id = "i-1", imagePath = null, driveImageId = "drive-1"))

        assertThat(prefetchDriveIds(items, cachedResolver)).isEmpty()
    }
}
