package com.itsluminous.samaroh.feature.expenses.sharetarget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Create-invoice share-target intent routing (ADR-078). */
@RunWith(RobolectricTestRunner::class)
class ShareTargetIntentsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun sendIntent(
        uri: Uri?,
        mimeType: String?,
    ): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            if (uri != null) putExtra(Intent.EXTRA_STREAM, uri)
        }

    private fun fileUri(name: String): Uri {
        val file = File.createTempFile(name.substringBeforeLast('.'), "." + name.substringAfterLast('.'), context.cacheDir)
        file.writeBytes(ByteArray(16))
        return Uri.fromFile(file)
    }

    @Test
    fun `an image SEND parses with the declared mime and file name`() {
        val uri = fileUri("bill.jpg")

        val parsed = ShareTargetIntents.parse(context, sendIntent(uri, "image/jpeg"))

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.uri).isEqualTo(uri)
        assertThat(parsed.mimeType).isEqualTo("image/jpeg")
        assertThat(parsed.displayName).endsWith(".jpg")
    }

    @Test
    fun `a pdf SEND parses`() {
        val parsed = ShareTargetIntents.parse(context, sendIntent(fileUri("invoice.pdf"), "application/pdf"))

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.mimeType).isEqualTo("application/pdf")
    }

    @Test
    fun `non-SEND actions and missing streams are ignored`() {
        assertThat(ShareTargetIntents.parse(context, null)).isNull()
        assertThat(ShareTargetIntents.parse(context, Intent(Intent.ACTION_VIEW))).isNull()
        // ACTION_SEND without EXTRA_STREAM (e.g. plain-text shares).
        assertThat(ShareTargetIntents.parse(context, sendIntent(uri = null, mimeType = "image/jpeg"))).isNull()
    }

    @Test
    fun `unsupported mime types are ignored`() {
        assertThat(ShareTargetIntents.parse(context, sendIntent(fileUri("notes.txt"), "text/plain"))).isNull()
        assertThat(ShareTargetIntents.parse(context, sendIntent(fileUri("clip.mp4"), "video/mp4"))).isNull()
    }

    @Test
    fun `holder is one-shot - consume clears the pending file`() {
        val holder = ShareTargetHolder()
        val file = SharedInvoiceFile(fileUri("bill.jpg"), "image/jpeg", "bill.jpg")

        holder.set(file)
        assertThat(holder.peek()).isEqualTo(file)
        assertThat(holder.consume()).isEqualTo(file)
        assertThat(holder.consume()).isNull()

        holder.set(file)
        holder.clear()
        assertThat(holder.peek()).isNull()
    }
}
