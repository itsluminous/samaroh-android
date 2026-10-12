package com.itsluminous.samaroh.core.invoice

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

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

    private fun drawnTexts(
        languageTag: String,
        data: InvoiceData = invoiceData(),
    ): List<String> {
        PdfInvoiceRenderer(localizedContext(languageTag)).render(data)
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

    // ---- 2026-10-12 layout change set (shared/docs/invoice-layout.md, ADR-100)

    @Test
    fun `header has no business type line`() {
        val texts = drawnTexts("en", invoiceData(business = Fixtures.business().copy(address = "12 Lake Rd")))
        assertThat(texts).contains("fixture-business")
        assertThat(texts).contains("12 Lake Rd")
        assertThat(texts).contains("fixture-owner")
        assertThat(texts).doesNotContain("Marriage Hall")
    }

    @Test
    fun `header and file name use the display invoice number`() {
        val rendered = PdfInvoiceRenderer(localizedContext("en")).render(invoiceData(invoiceNumber = "88"))
        val texts =
            ShadowPdfDocument.issuedCanvases.flatMap { canvas ->
                val shadow = shadowOf(canvas)
                (0 until shadow.textHistoryCount).map { shadow.getDrawnTextEvent(it).text }
            }
        assertThat(texts).contains("INV-0088")
        assertThat(texts).doesNotContain("88")
        assertThat(File(rendered.filePath).name).isEqualTo("INV-0088.pdf")
    }

    @Test
    fun `custom prefix drives both the display number and the file name`() {
        val business = Fixtures.business().copy(invoicePrefix = "SGH")
        val rendered = PdfInvoiceRenderer(localizedContext("en")).render(invoiceData(invoiceNumber = "42", business = business))
        assertThat(File(rendered.filePath).name).isEqualTo("SGH-0042.pdf")
    }

    @Test
    fun `notes block prints the booking notes verbatim after the event block and not payment notes`() {
        val texts =
            drawnTexts(
                "en",
                invoiceData(
                    notes = "  Stage by 4 pm \uD83C\uDF89\nMandap: red & gold  ",
                    paymentNotes = "Imported \u2014 settled on event day",
                ),
            )
        val eventIndex = texts.indexOf("Wedding")
        // First "Notes" is the section title (the second is the payment table's column header).
        val titleIndex = texts.indexOf("Notes")
        assertThat(titleIndex).isGreaterThan(eventIndex)
        assertThat(texts.subList(titleIndex + 1, titleIndex + 3)).containsExactly("Stage by 4 pm", "Mandap: red & gold").inOrder()
        // Payment-row notes stay in the payment table (they are NOT the booking's notes) …
        assertThat(texts.any { it.startsWith("Imported \u2014 settled") }).isTrue()
        // … and the booking notes section comes BEFORE the payment history.
        assertThat(titleIndex).isLessThan(texts.indexOf("Payment history"))
        assertThat(texts.count { it == "Notes" }).isEqualTo(2)
        // No emoji anywhere (ADR-035).
        assertThat(texts.joinToString("\n").any { it.isSurrogate() }).isFalse()
    }

    @Test
    fun `no notes block when the booking has blank notes`() {
        // Only the payment table's "Notes" column header remains.
        assertThat(drawnTexts("en", invoiceData(notes = "   ")).count { it == "Notes" }).isEqualTo(1)
        ShadowPdfDocument.issuedCanvases.clear()
        assertThat(drawnTexts("en", invoiceData(notes = null)).count { it == "Notes" }).isEqualTo(1)
    }

    @Test
    fun `long notes are never truncated`() {
        // (Line breaking itself is pinned in InvoiceTextTest; Robolectric's legacy canvas
        // measures text as zero width, so everything lands on one line here.)
        val longNotes = (1..60).joinToString(" ") { "word$it" }
        val texts = drawnTexts("en", invoiceData(notes = longNotes))
        val printed = texts.filter { it.startsWith("word") }
        assertThat(printed.joinToString(" ")).isEqualTo(longNotes)
    }

    private companion object {
        const val WEDDING_ICON = "\uD83D\uDC92"
    }
}
