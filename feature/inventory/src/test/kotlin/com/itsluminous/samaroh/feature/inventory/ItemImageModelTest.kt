package com.itsluminous.samaroh.feature.inventory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.request.ImageRequest
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.image.ItemImageSource
import com.itsluminous.samaroh.feature.inventory.image.itemImageModel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** ADR-023: Coil model mapping for the two `image_path` forms (+ the unconfigured case). */
@RunWith(RobolectricTestRunner::class)
class ItemImageModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `local file source maps to a File model`() {
        val model = itemImageModel(context, ItemImageSource.LocalFile("/data/user/0/app/files/inventory-images/i.webp"))

        assertThat(model).isEqualTo(File("/data/user/0/app/files/inventory-images/i.webp"))
    }

    @Test
    fun `remote source maps to an authenticated request with stable cache keys`() {
        val source =
            ItemImageSource.RemoteObject(
                url = "https://example.supabase.co/storage/v1/object/authenticated/inventory-images/biz/item/1.webp",
                accessToken = "jwt-token",
                cacheKey = "biz/item/1.webp",
            )

        val request = itemImageModel(context, source) as ImageRequest

        assertThat(request.data).isEqualTo(source.url)
        // Keys are the object path, NOT the URL — cached photos survive token changes.
        assertThat(request.memoryCacheKey?.key).isEqualTo("biz/item/1.webp")
        assertThat(request.diskCacheKey).isEqualTo("biz/item/1.webp")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer jwt-token")
    }

    @Test
    fun `remote source without a session still builds a cache-served request`() {
        val source =
            ItemImageSource.RemoteObject(
                url = "https://example.supabase.co/storage/v1/object/authenticated/inventory-images/biz/item/1.webp",
                accessToken = null,
                cacheKey = "biz/item/1.webp",
            )

        val request = itemImageModel(context, source) as ImageRequest

        assertThat(request.headers["Authorization"]).isNull()
        assertThat(request.diskCacheKey).isEqualTo("biz/item/1.webp")
    }

    @Test
    fun `unavailable source maps to no model`() {
        assertThat(itemImageModel(context, ItemImageSource.Unavailable)).isNull()
    }
}
