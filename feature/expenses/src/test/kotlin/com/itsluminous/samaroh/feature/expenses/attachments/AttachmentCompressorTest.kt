package com.itsluminous.samaroh.feature.expenses.attachments

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
// NATIVE graphics is REQUIRED for fidelity: legacy shadows return a Bitmap from the
// inJustDecodeBounds pass where real Android returns null — exactly the divergence that
// let the v0.8.2 "Couldn't add that file" bug ship with green tests.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentCompressorTest {
    private lateinit var context: Context
    private lateinit var compressor: AttachmentCompressor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        compressor = AttachmentCompressor(context)
    }

    @Test
    fun `oversized image is scaled to the max dimension and re-encoded as jpeg`() =
        runTest {
            val source = writeBitmap(width = 4096, height = 1024)

            val prepared = prepared(compressor.prepare(Uri.fromFile(source), "image/png", source.name))

            assertThat(prepared.mimeType).isEqualTo(AttachmentCompressor.MIME_JPEG)
            val bounds = decodeBounds(prepared.file)
            assertThat(maxOf(bounds.first, bounds.second)).isAtMost(2048)
            // Invoice stays UNCROPPED: the 4:1 aspect ratio survives compression exactly.
            assertThat(bounds.first / bounds.second).isEqualTo(4)
            assertThat(bounds.first % bounds.second).isEqualTo(0)
        }

    @Test
    fun `small image is not upscaled`() =
        runTest {
            val source = writeBitmap(width = 640, height = 480)

            val prepared = prepared(compressor.prepare(Uri.fromFile(source), "image/png", source.name))

            assertThat(decodeBounds(prepared.file)).isEqualTo(640 to 480)
        }

    @Test
    fun `already-efficient image keeps its original bytes and mime`() =
        runTest {
            // A tiny flat-colour PNG is smaller than any JPEG re-encode of it; with no
            // resize and no EXIF rotation there is nothing to fix, so the original wins.
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366AA.toInt()) }
            val source = File(context.cacheDir, "tiny.png")
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()

            val prepared = prepared(compressor.prepare(Uri.fromFile(source), "image/png", source.name))

            assertThat(prepared.mimeType).isEqualTo("image/png")
            assertThat(prepared.file.readBytes()).isEqualTo(source.readBytes())
        }

    @Test
    fun `exif-rotated image is stored upright`() =
        runTest {
            val source = writeJpeg(width = 320, height = 240)
            ExifInterface(source.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }

            val prepared = prepared(compressor.prepare(Uri.fromFile(source), "image/jpeg", source.name))

            // Rotation applied → re-encode (never keep-original) → upright dimensions.
            assertThat(prepared.mimeType).isEqualTo(AttachmentCompressor.MIME_JPEG)
            assertThat(decodeBounds(prepared.file)).isEqualTo(240 to 320)
        }

    @Test
    fun `pdf is copied byte-identically`() =
        runTest {
            val bytes = ByteArray(1024) { (it % 251).toByte() }
            val source = File(context.cacheDir, "invoice.pdf").apply { writeBytes(bytes) }

            val prepared = prepared(compressor.prepare(Uri.fromFile(source), AttachmentCompressor.MIME_PDF, source.name))

            assertThat(prepared.mimeType).isEqualTo(AttachmentCompressor.MIME_PDF)
            assertThat(prepared.file.readBytes()).isEqualTo(bytes)
            assertThat(prepared.fileName).endsWith(".pdf")
        }

    @Test
    fun `document over the size cap is refused as too large`() =
        runTest {
            val capped = AttachmentCompressor(context, maxDocumentBytes = 1_024L)
            val source = File(context.cacheDir, "huge.pdf").apply { writeBytes(ByteArray(4_096)) }

            val result = capped.prepare(Uri.fromFile(source), AttachmentCompressor.MIME_PDF, source.name)

            assertThat(result).isEqualTo(AttachmentCompressor.PrepareResult.TooLarge)
            // The bounded copy must not leave a partial file behind.
            assertThat(capped.attachmentsDir().listFiles()!!.filter { it.name.startsWith("huge") }).isEmpty()
        }

    @Test
    fun `camera capture is compressed via the same image path`() =
        runTest {
            // Regression: prepareCapturedImage funnels into compressImage, whose bounds
            // pass must never null-check the decode result (null by contract on device).
            val capture = compressor.newCaptureFile()
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            capture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()

            val prepared = prepared(compressor.prepareCapturedImage(capture))

            assertThat(prepared.mimeType).isEqualTo(AttachmentCompressor.MIME_JPEG)
            assertThat(decodeBounds(prepared.file)).isEqualTo(320 to 240)
        }

    @Test
    fun `unreadable content returns unreadable`() =
        runTest {
            val missing = File(context.cacheDir, "does-not-exist.png")

            val result = compressor.prepare(Uri.fromFile(missing), "image/png", missing.name)

            assertThat(result).isEqualTo(AttachmentCompressor.PrepareResult.Unreadable)
        }

    @Test
    fun `output lands in the app-private attachments dir`() =
        runTest {
            val source = writeBitmap(width = 100, height = 100)

            val prepared = prepared(compressor.prepare(Uri.fromFile(source), "image/png", source.name))

            assertThat(prepared.file.parentFile).isEqualTo(compressor.attachmentsDir())
        }

    private fun prepared(result: AttachmentCompressor.PrepareResult): AttachmentCompressor.Prepared {
        assertThat(result).isInstanceOf(AttachmentCompressor.PrepareResult.Ready::class.java)
        return (result as AttachmentCompressor.PrepareResult.Ready).prepared
    }

    private fun writeBitmap(
        width: Int,
        height: Int,
    ): File {
        val bitmap = noisyBitmap(width, height)
        val file = File(context.cacheDir, "source-$width-$height.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun writeJpeg(
        width: Int,
        height: Int,
    ): File {
        val bitmap = noisyBitmap(width, height)
        val file = File(context.cacheDir, "source-$width-$height.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        return file
    }

    /** Seeded random pixels: noisy sources keep re-encode sizes honest (no trivial wins). */
    private fun noisyBitmap(
        width: Int,
        height: Int,
    ): Bitmap {
        val random = Random(seed = 7)
        val pixels = IntArray(width * height) { random.nextInt() or (0xFF shl 24) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun decodeBounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth to options.outHeight
    }
}
