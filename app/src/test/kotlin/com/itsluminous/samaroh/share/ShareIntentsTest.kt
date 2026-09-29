package com.itsluminous.samaroh.share

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

/** Save to Samaroh share-target intent parsing (ADR-086). */
@RunWith(RobolectricTestRunner::class)
class ShareIntentsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun fileUri(name: String): Uri {
        val file = File.createTempFile(name.substringBeforeLast('.'), "." + name.substringAfterLast('.'), context.cacheDir)
        file.writeBytes(ByteArray(16))
        return Uri.fromFile(file)
    }

    @Test
    fun `a single SEND parses into one shared file with the declared mime`() {
        val uri = fileUri("bill.pdf")
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
            }
        val parsed = ShareIntents.parse(context, intent)
        assertThat(parsed).hasSize(1)
        assertThat(parsed.single().uri).isEqualTo(uri)
        assertThat(parsed.single().mimeType).isEqualTo("application/pdf")
        assertThat(parsed.single().displayName).endsWith(".pdf")
    }

    @Test
    fun `SEND_MULTIPLE keeps every stream and falls back to octet-stream for wildcards`() {
        val uris = arrayListOf(fileUri("photo.jpg"), fileUri("letter.doc"))
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        val parsed = ShareIntents.parse(context, intent)
        assertThat(parsed.map { it.uri }).containsExactlyElementsIn(uris).inOrder()
        // A wildcard declaration is never trusted as the concrete MIME.
        assertThat(parsed.map { it.mimeType }).doesNotContain("*/*")
    }

    @Test
    fun `non-share intents and shares without a stream parse to nothing`() {
        assertThat(ShareIntents.parse(context, null)).isEmpty()
        assertThat(ShareIntents.parse(context, Intent(Intent.ACTION_VIEW))).isEmpty()
        assertThat(ShareIntents.parse(context, Intent(Intent.ACTION_SEND).apply { type = "text/plain" })).isEmpty()
    }
}
