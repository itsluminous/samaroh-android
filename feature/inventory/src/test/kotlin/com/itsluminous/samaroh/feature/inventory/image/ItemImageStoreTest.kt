package com.itsluminous.samaroh.feature.inventory.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.designsystem.imaging.CompressionSpec
import com.itsluminous.samaroh.core.designsystem.imaging.ImageCompression
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
// NATIVE graphics: real encode/decode, so sizes and dimensions mean what they say
// (legacy shadows lie — the ee55b5a lesson).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ItemImageStoreTest {
    private lateinit var context: Context
    private lateinit var store: LocalItemImageStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = LocalItemImageStore(context)
    }

    @Test
    fun `cropped square is stored as webp within the dimension cap`() =
        runTest {
            val source = noisyBitmap(640, 640)

            val path = store.compressItemImage(source, itemId = "item-1")

            assertThat(path).isNotNull()
            val bounds = decodeBounds(File(path!!))
            assertThat(bounds.first).isEqualTo(ITEM_IMAGE_MAX_DIMENSION_PX)
            assertThat(bounds.second).isEqualTo(ITEM_IMAGE_MAX_DIMENSION_PX)
            assertThat(path).endsWith("item-1.webp")
        }

    @Test
    fun `non-square input is defensively center-cropped square`() =
        runTest {
            val source = noisyBitmap(800, 400)

            val path = store.compressItemImage(source, itemId = "item-2")

            val bounds = decodeBounds(File(path!!))
            assertThat(bounds.first).isEqualTo(bounds.second)
        }

    @Test
    fun `small image is not upscaled`() =
        runTest {
            val source = noisyBitmap(200, 200)

            val path = store.compressItemImage(source, itemId = "item-3")

            assertThat(decodeBounds(File(path!!))).isEqualTo(200 to 200)
        }

    @Test
    fun `item level squeezes harder than the document level would`() =
        runTest {
            // The ~50% level must produce a smaller file than a light re-encode of the
            // SAME pixels at the same dimensions — the owner's ordering, end to end.
            val source = noisyBitmap(640, 640)
            val path = store.compressItemImage(source, itemId = "item-4")!!

            val lightSpec = CompressionSpec(quality = 90, maxDimensionPx = 320, format = CompressionSpec.OutputFormat.WEBP)
            val lightFile = File(context.cacheDir, "light-ref.webp")
            ImageCompression.encodeToFile(source, lightSpec, lightFile)

            assertThat(File(path).length()).isLessThan(lightFile.length())
        }

    @Test
    fun `delete removes the stored file`() =
        runTest {
            val source = noisyBitmap(320, 320)
            val path = store.compressItemImage(source, itemId = "item-5")!!
            assertThat(File(path).exists()).isTrue()

            store.deleteItemImage("item-5")

            assertThat(File(path).exists()).isFalse()
        }

    private fun noisyBitmap(
        width: Int,
        height: Int,
    ): Bitmap {
        val random = Random(seed = 11)
        val pixels = IntArray(width * height) { random.nextInt() or (0xFF shl 24) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun decodeBounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth to options.outHeight
    }
}
