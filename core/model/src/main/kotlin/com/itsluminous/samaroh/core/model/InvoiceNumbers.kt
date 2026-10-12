package com.itsluminous.samaroh.core.model

/**
 * Invoice-number DISPLAY form (shared/invoice/layout-spec.md "Display form", ADR-100): the
 * ONE rule every surface uses when showing `bookings.invoice_number` — PDF header, text
 * receipt, booking card/detail line, Google Calendar description and the PDF file name.
 * The stored value never changes.
 */
object InvoiceNumbers {
    /** Default `businesses.invoice_prefix`. */
    const val DEFAULT_PREFIX = "INV"

    private val bareDigits = Regex("^[0-9]+$")
    private val unsafeFileChars = Regex("[^A-Za-z0-9._-]")

    /**
     * Bare digits (an owner-typed paper-register number such as `88`, ADR-020) →
     * `{prefix}-{n:04d}` (`88` → `INV-0088`, `0088` → `INV-0088`, `12345` → `INV-12345`);
     * anything else (auto `INV-2026-0042`, `A-12`) verbatim. Blank prefix falls back to
     * [DEFAULT_PREFIX].
     */
    fun display(
        stored: String,
        prefix: String = DEFAULT_PREFIX,
    ): String {
        val trimmed = stored.trim()
        if (!bareDigits.matches(trimmed)) return trimmed
        val n = trimmed.toBigInteger()
        val p = prefix.trim().ifBlank { DEFAULT_PREFIX }
        return "$p-${n.toString().padStart(4, '0')}"
    }

    /** `{display}.pdf`, characters outside `[A-Za-z0-9._-]` replaced by `-` (layout-spec § File name). */
    fun pdfFileName(
        stored: String,
        prefix: String = DEFAULT_PREFIX,
    ): String = unsafeFileChars.replace(display(stored, prefix), "-") + ".pdf"
}
