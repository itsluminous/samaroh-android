package com.itsluminous.samaroh.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The shared display-form table (shared/invoice/layout-spec.md "Display form", ADR-100). */
class InvoiceNumbersTest {
    @Test
    fun `bare digits become prefix dash zero padded`() {
        assertThat(InvoiceNumbers.display("88", "INV")).isEqualTo("INV-0088")
        assertThat(InvoiceNumbers.display("0088", "INV")).isEqualTo("INV-0088")
        assertThat(InvoiceNumbers.display("12345", "INV")).isEqualTo("INV-12345")
        assertThat(InvoiceNumbers.display("88", "SGH")).isEqualTo("SGH-0088")
    }

    @Test
    fun `anything else is verbatim`() {
        assertThat(InvoiceNumbers.display("INV-2026-0042", "INV")).isEqualTo("INV-2026-0042")
        assertThat(InvoiceNumbers.display("A-12", "INV")).isEqualTo("A-12")
        assertThat(InvoiceNumbers.display("SGH-2026-0042", "INV")).isEqualTo("SGH-2026-0042")
    }

    @Test
    fun `blank prefix and surrounding whitespace fall back sanely`() {
        assertThat(InvoiceNumbers.display(" 7 ", "  ")).isEqualTo("INV-0007")
        assertThat(InvoiceNumbers.display("88")).isEqualTo("INV-0088")
    }

    @Test
    fun `pdf file name is the display form sanitised`() {
        assertThat(InvoiceNumbers.pdfFileName("88", "INV")).isEqualTo("INV-0088.pdf")
        assertThat(InvoiceNumbers.pdfFileName("SGH-2026-0042", "INV")).isEqualTo("SGH-2026-0042.pdf")
        assertThat(InvoiceNumbers.pdfFileName("A/12 b", "INV")).isEqualTo("A-12-b.pdf")
        // Non-ASCII is replaced character by character (3 letters + 1 space → 4 dashes).
        assertThat(InvoiceNumbers.pdfFileName("बिल 5", "INV")).isEqualTo("----5.pdf")
    }
}
