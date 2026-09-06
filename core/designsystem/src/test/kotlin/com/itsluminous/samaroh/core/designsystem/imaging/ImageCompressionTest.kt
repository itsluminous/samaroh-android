package com.itsluminous.samaroh.core.designsystem.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
// NATIVE graphics is REQUIRED for fidelity: legacy shadows encode fake bytes whose sizes
// don't respond to quality, and return a Bitmap from the inJustDecodeBounds pass where
// real Android returns null (the ee55b5a lesson).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageCompressionTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- CompressionSpec levels ---

    @Test
    fun `lower quality produces a smaller file for the same pixels and format`() {
        val bitmap = noisyBitmap(width = 512, height = 512)
        val light = CompressionSpec(quality = 90, maxDimensionPx = 512, format = CompressionSpec.OutputFormat.JPEG)
        val heavy = CompressionSpec(quality = 50, maxDimensionPx = 512, format = CompressionSpec.OutputFormat.JPEG)

        val lightBytes = ImageCompression.encodeToFile(bitmap, light, newFile("light.jpg"))
        val heavyBytes = ImageCompression.encodeToFile(bitmap, heavy, newFile("heavy.jpg"))

        // The owner's "~10% vs ~50% compression" must actually order output sizes.
        assertThat(heavyBytes).isLessThan(lightBytes)
        assertThat(heavyBytes).isGreaterThan(0L)
    }

    @Test
    fun `item photo level squeezes harder than document level for the same source`() {
        val bitmap = noisyBitmap(width = 640, height = 640)

        val doc = ImageCompression.encodeToFile(bitmap, CompressionSpec.DocumentLight, newFile("doc.jpg"))
        val item = ImageCompression.encodeToFile(bitmap, CompressionSpec.ItemPhoto, newFile("item.webp"))

        assertThat(item).isLessThan(doc)
    }

    @Test
    fun `item photo preset caps the longest side at 320`() {
        val bitmap = noisyBitmap(width = 640, height = 480)

        ImageCompression.encodeToFile(bitmap, CompressionSpec.ItemPhoto, newFile("item-dims.webp"))

        val (w, h) = decodeBounds(newFile("item-dims.webp"))
        assertThat(maxOf(w, h)).isAtMost(CompressionSpec.ItemPhoto.maxDimensionPx)
        // Aspect preserved (4:3).
        assertThat(w * 3).isEqualTo(h * 4)
    }

    @Test
    fun `images are never upscaled`() {
        val bitmap = noisyBitmap(width = 100, height = 60)

        ImageCompression.encodeToFile(bitmap, CompressionSpec.DocumentLight, newFile("small.jpg"))

        assertThat(decodeBounds(newFile("small.jpg"))).isEqualTo(100 to 60)
    }

    @Test
    fun `sample size stays a power of two near the bound`() {
        assertThat(ImageCompression.sampleSizeFor(longestEdgePx = 320, maxDimensionPx = 2048)).isEqualTo(1)
        // Boundary: 4096/2 == 2048 lands exactly ON the cap, which counts as reached.
        assertThat(ImageCompression.sampleSizeFor(longestEdgePx = 4096, maxDimensionPx = 2048)).isEqualTo(2)
        assertThat(ImageCompression.sampleSizeFor(longestEdgePx = 8192, maxDimensionPx = 2048)).isEqualTo(4)
        assertThat(ImageCompression.sampleSizeFor(longestEdgePx = 3200, maxDimensionPx = 320)).isEqualTo(8)
    }

    // --- decodeUprightImage (EXIF) ---

    @Test
    fun `exif-rotated jpeg decodes upright with swapped dimensions`() =
        runTest {
            val file = writeJpeg(width = 320, height = 240)
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }

            val upright = decodeUprightImage(context, Uri.fromFile(file), maxDimensionPx = 2048)

            assertThat(upright).isNotNull()
            assertThat(upright!!.rotationDegrees).isEqualTo(90)
            assertThat(upright.bitmap.width).isEqualTo(240)
            assertThat(upright.bitmap.height).isEqualTo(320)
            assertThat(upright.wasDownsized).isFalse()
        }

    @Test
    fun `unrotated jpeg reports zero rotation and no downsize`() =
        runTest {
            val file = writeJpeg(width = 320, height = 240)

            val upright = decodeUprightImage(context, Uri.fromFile(file), maxDimensionPx = 2048)

            assertThat(upright).isNotNull()
            assertThat(upright!!.rotationDegrees).isEqualTo(0)
            assertThat(upright.bitmap.width to upright.bitmap.height).isEqualTo(320 to 240)
        }

    @Test
    fun `oversized source is bounded and flagged as downsized`() =
        runTest {
            val file = writeJpeg(width = 4096, height = 1024)

            val upright = decodeUprightImage(context, Uri.fromFile(file), maxDimensionPx = 2048)

            assertThat(upright).isNotNull()
            assertThat(upright!!.wasDownsized).isTrue()
            // The decode pass is power-of-two coarse: bounded near, never way above, the cap.
            assertThat(maxOf(upright.bitmap.width, upright.bitmap.height)).isAtMost(4096 / 2)
        }

    @Test
    fun `unreadable content returns null`() =
        runTest {
            val missing = File(context.cacheDir, "missing.jpg")

            assertThat(decodeUprightImage(context, Uri.fromFile(missing), maxDimensionPx = 2048)).isNull()
        }

    // --- helpers ---

    /** Seeded random pixels so encoder quality actually changes output size. */
    private fun noisyBitmap(
        width: Int,
        height: Int,
    ): Bitmap {
        val random = Random(seed = 42)
        val pixels = IntArray(width * height) { random.nextInt() or (0xFF shl 24) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun writeJpeg(
        width: Int,
        height: Int,
    ): File {
        val bitmap = noisyBitmap(width, height)
        val file = File(context.cacheDir, "jpeg-$width-$height-${System.nanoTime()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        return file
    }

    private fun newFile(name: String): File = File(context.cacheDir, name)

    private fun decodeBounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth to options.outHeight
    }
}
