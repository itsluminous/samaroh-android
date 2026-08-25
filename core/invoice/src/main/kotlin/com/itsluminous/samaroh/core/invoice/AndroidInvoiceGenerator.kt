package com.itsluminous.samaroh.core.invoice

import com.itsluminous.samaroh.core.data.invoice.InvoiceGenerator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The frozen `core:data` [InvoiceGenerator] contract (ADR-006), implemented per spec §4.1:
 * PDF via [PdfInvoiceRenderer] (saved to app storage, absolute path returned) and the
 * localized plain-text receipt via [InvoiceTextBuilder]. Both assign the immutable
 * invoice number on first use, so text and PDF always agree.
 */
@Singleton
class AndroidInvoiceGenerator
    @Inject
    constructor(
        private val loader: InvoiceDataLoader,
        private val renderer: PdfInvoiceRenderer,
        private val textBuilder: InvoiceTextBuilder,
    ) : InvoiceGenerator {
        override suspend fun generateInvoicePdf(bookingId: String): Result<String> =
            runCatching { renderer.render(loader.load(bookingId)).filePath }

        override suspend fun buildInvoiceText(bookingId: String): String = textBuilder.build(loader.load(bookingId))
    }
