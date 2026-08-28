package com.itsluminous.samaroh.core.invoice

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Asserts WHAT the PDF prints (layout-spec §3, ADR-035): the event block carries the
 * localized event-type label WITHOUT the event icon emoji — emoji glyphs render
 * inconsistently across PDF fonts/viewers. LEGACY graphics mode makes `shadowOf(canvas)`
 * record every `drawText` call, so these tests see the exact strings the renderer draws.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(shadows = [ShadowPdfDocument::class])
class PdfInvoiceRendererContentTest {
    @Before
    fun clearRecordedCanvases() {
        ShadowPdfDocument.issuedCanvases.clear()
    }

    private fun drawnTexts(languageTag: String): List<String> {
        PdfInvoiceRenderer(localizedContext(languageTag)).render(invoiceData())
        return ShadowPdfDocument.issuedCanvases.flatMap { canvas ->
            val shadow = shadowOf(canvas)
            (0 until shadow.textHistoryCount).map { shadow.getDrawnTextEvent(it).text }
        }
    }

    @Test
    fun `english pdf prints the event type label without the emoji icon`() {
        val texts = drawnTexts("en")

        // The fixture booking is a wedding with icon 💒 (U+1F492).
        assertThat(texts).contains("Wedding")
        val all = texts.joinToString("\n")
        assertThat(all).doesNotContain(WEDDING_ICON)
        // No emoji at all: every emoji is an astral code point, i.e. a surrogate pair.
        assertThat(all.any { it.isSurrogate() }).isFalse()
    }

    @Test
    fun `hindi pdf prints the event type label without the emoji icon`() {
        val texts = drawnTexts("hi")

        assertThat(texts).contains("शादी")
        val all = texts.joinToString("\n")
        assertThat(all).doesNotContain(WEDDING_ICON)
        assertThat(all.any { it.isSurrogate() }).isFalse()
    }

    private companion object {
        const val WEDDING_ICON = "\uD83D\uDC92"
    }
}
