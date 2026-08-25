package com.itsluminous.samaroh.feature.expenses.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
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

            val prepared = compressor.prepare(Uri.fromFile(source), "image/png", source.name)

            assertThat(prepared).isNotNull()
            assertThat(prepared!!.mimeType).isEqualTo(AttachmentCompressor.MIME_JPEG)
            val bounds = decodeBounds(prepared.file)
            assertThat(maxOf(bounds.first, bounds.second)).isAtMost(AttachmentCompressor.MAX_DIMENSION)
            // Aspect ratio preserved (4:1).
            assertThat(bounds.first / bounds.second).isEqualTo(4)
        }

    @Test
    fun `small image is not upscaled`() =
        runTest {
            val source = writeBitmap(width = 640, height = 480)

            val prepared = compressor.prepare(Uri.fromFile(source), "image/png", source.name)

            assertThat(prepared).isNotNull()
            val bounds = decodeBounds(prepared!!.file)
            assertThat(bounds).isEqualTo(640 to 480)
        }

    @Test
    fun `pdf is copied byte-identically`() =
        runTest {
            val bytes = ByteArray(1024) { (it % 251).toByte() }
            val source = File(context.cacheDir, "invoice.pdf").apply { writeBytes(bytes) }

            val prepared = compressor.prepare(Uri.fromFile(source), AttachmentCompressor.MIME_PDF, source.name)

            assertThat(prepared).isNotNull()
            assertThat(prepared!!.mimeType).isEqualTo(AttachmentCompressor.MIME_PDF)
            assertThat(prepared.file.readBytes()).isEqualTo(bytes)
            assertThat(prepared.fileName).endsWith(".pdf")
        }

    @Test
    fun `unreadable content returns null`() =
        runTest {
            val missing = File(context.cacheDir, "does-not-exist.png")

            val prepared = compressor.prepare(Uri.fromFile(missing), "image/png", missing.name)

            assertThat(prepared).isNull()
        }

    @Test
    fun `output lands in the app-private attachments dir`() =
        runTest {
            val source = writeBitmap(width = 100, height = 100)

            val prepared = compressor.prepare(Uri.fromFile(source), "image/png", source.name)

            assertThat(prepared!!.file.parentFile).isEqualTo(compressor.attachmentsDir())
        }

    private fun writeBitmap(
        width: Int,
        height: Int,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "source-$width-$height.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun decodeBounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth to options.outHeight
    }
}
