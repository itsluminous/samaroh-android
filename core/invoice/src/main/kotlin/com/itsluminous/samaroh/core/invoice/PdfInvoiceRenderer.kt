package com.itsluminous.samaroh.core.invoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android `PdfDocument` renderer implementing the invoice layout contract
 * (shared/invoice/layout-spec.md): A4 portrait, 40 pt margins, header band with logo +
 * business identity + invoice number, customer and event blocks, amounts, payment
 * history table, tonal balance-due band, centered footer. All text is localized
 * (`invoice.*` catalog keys) and all amounts go through [AmountFormatter] (Indian digit
 * grouping, paise only when non-zero).
 */
@Singleton
class PdfInvoiceRenderer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        data class Rendered(
            val filePath: String,
            val pageCount: Int,
            val sizeBytes: Long,
        )

        fun render(data: InvoiceData): Rendered {
            val ctx = context.withAppLocale()
            val document = PdfDocument()
            val painter = Painter(ctx, document, data)
            try {
                painter.paint()
                val outDir = File(context.filesDir, INVOICE_DIR).apply { mkdirs() }
                val outFile = File(outDir, "${data.invoiceNumber}$PDF_EXTENSION")
                FileOutputStream(outFile).use { document.writeTo(it) }
                return Rendered(outFile.absolutePath, painter.pageCount, outFile.length())
            } finally {
                document.close()
            }
        }

        /** One render pass; owns the page/cursor state. */
        private class Painter(
            private val ctx: Context,
            private val document: PdfDocument,
            private val data: InvoiceData,
        ) {
            private val dateFormat =
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(ctx.displayLocale())

            private val bodyPaint = paint(COLOR_BODY, BODY_SIZE)
            private val bodyBoldPaint = paint(COLOR_BODY, BODY_SIZE, bold = true)
            private val greyPaint = paint(COLOR_GREY, BODY_SIZE)
            private val sectionPaint = paint(COLOR_BODY, SECTION_SIZE, bold = true)
            private val accentSectionPaint = paint(COLOR_ACCENT, SECTION_SIZE, bold = true)
            private val businessNamePaint = paint(COLOR_BODY, NAME_SIZE, bold = true)
            private val footerPaint =
                paint(COLOR_GREY, FOOTER_SIZE).apply { textAlign = Paint.Align.CENTER }
            private val rulePaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = COLOR_ACCENT
                    strokeWidth = 1f
                    style = Paint.Style.STROKE
                }
            private val bandFillPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = COLOR_TONAL
                    style = Paint.Style.FILL
                }

            var pageCount = 0
                private set

            private lateinit var page: PdfDocument.Page
            private lateinit var canvas: Canvas
            private var y = 0f

            fun paint() {
                newPage()
                drawHeader()
                drawCustomerBlock()
                drawEventBlock()
                drawAmounts()
                drawPaymentTable()
                drawBalanceBand()
                drawFooter()
                document.finishPage(page)
            }

            // ------------------------------------------------------------ sections

            private fun drawHeader() {
                val business = data.business
                val logo = loadLogo(business.logoPath)
                var textX = MARGIN
                if (logo != null) {
                    val rect = RectF(MARGIN, y, MARGIN + LOGO_SIZE, y + LOGO_SIZE)
                    val clip = Path().apply { addRoundRect(rect, LOGO_RADIUS, LOGO_RADIUS, Path.Direction.CW) }
                    canvas.save()
                    canvas.clipPath(clip)
                    canvas.drawBitmap(logo, null, rect, null)
                    canvas.restore()
                    textX = MARGIN + LOGO_SIZE + 12f
                }
                var textY = y + NAME_SIZE
                canvas.drawText(business.name, textX, textY, businessNamePaint)
                textY += LINE_GAP + BODY_SIZE
                for (line in listOfNotNull(business.businessType, business.address, business.ownerName)) {
                    canvas.drawText(line, textX, textY, greyPaint)
                    textY += BODY_SIZE + 4f
                }
                // Right-aligned column: localized title, number, issue date.
                var rightY = y + SECTION_SIZE
                drawRight(ctx.getString(R.string.invoice_title), rightY, accentSectionPaint)
                rightY += SECTION_SIZE + 6f
                drawRight(data.invoiceNumber, rightY, bodyPaint)
                rightY += BODY_SIZE + 6f
                drawRight(date(data.issueDate), rightY, bodyPaint)

                y = maxOf(textY, y + LOGO_SIZE, rightY) + 12f
                canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rulePaint)
                y += RULE_GAP
            }

            private fun drawCustomerBlock() {
                line(ctx.getString(R.string.invoice_billed_to), bodyBoldPaint)
                line(data.booking.customerName, bodyPaint)
                data.booking.customerPhone?.let { line(it, bodyPaint) }
                y += BLOCK_GAP
            }

            private fun drawEventBlock() {
                val booking = data.booking
                // No event icon/emoji in PDF output (layout-spec §3, ADR-035): emoji
                // glyphs render inconsistently across PDF fonts/viewers. The icon stays
                // in the app UI and the text receipt.
                line(EventTypeLabels.label(ctx, booking.eventType), bodyBoldPaint)
                val dates =
                    if (booking.startDate == booking.endDate) {
                        date(booking.startDate)
                    } else {
                        "${date(booking.startDate)} \u2013 ${date(booking.endDate)}"
                    }
                val times =
                    listOfNotNull(booking.startTime, booking.endTime)
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(" \u2013 ")
                line(if (times != null) "$dates · $times" else dates, bodyPaint)
                y += BLOCK_GAP
            }

            private fun drawAmounts() {
                amountRow(ctx.getString(R.string.invoice_total_amount), data.booking.totalAmountPaise, bodyBoldPaint, bodyBoldPaint)
                if (data.booking.securityDepositPaise > 0) {
                    amountRow(ctx.getString(R.string.invoice_security_deposit), data.booking.securityDepositPaise, bodyPaint, bodyPaint)
                }
                y += BLOCK_GAP
            }

            private fun drawPaymentTable() {
                line(ctx.getString(R.string.invoice_payment_history), sectionPaint)
                y += 4f
                // Header row with accent bottom border.
                ensureSpace(ROW_HEIGHT * 2)
                canvas.drawText(ctx.getString(R.string.invoice_table_date), COL_DATE, y + BODY_SIZE, bodyBoldPaint)
                canvas.drawText(ctx.getString(R.string.invoice_table_method), COL_METHOD, y + BODY_SIZE, bodyBoldPaint)
                canvas.drawText(ctx.getString(R.string.invoice_table_notes), COL_NOTES, y + BODY_SIZE, bodyBoldPaint)
                drawRight(ctx.getString(R.string.invoice_table_amount), y + BODY_SIZE, bodyBoldPaint)
                y += ROW_HEIGHT
                canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rulePaint)
                y += 6f
                if (data.payments.isEmpty()) {
                    line(ctx.getString(R.string.invoice_no_payments), greyPaint)
                } else {
                    for (payment in data.payments) {
                        ensureSpace(ROW_HEIGHT)
                        canvas.drawText(date(payment.paidOn), COL_DATE, y + BODY_SIZE, bodyPaint)
                        canvas.drawText(ctx.getString(payment.method.labelRes()), COL_METHOD, y + BODY_SIZE, bodyPaint)
                        payment.notes?.let { canvas.drawText(it.take(NOTES_MAX_CHARS), COL_NOTES, y + BODY_SIZE, bodyPaint) }
                        drawRight(AmountFormatter.format(payment.amountPaise), y + BODY_SIZE, bodyPaint)
                        y += ROW_HEIGHT
                    }
                }
                ensureSpace(ROW_HEIGHT)
                canvas.drawText(ctx.getString(R.string.invoice_total_paid), COL_DATE, y + BODY_SIZE, bodyBoldPaint)
                drawRight(AmountFormatter.format(data.totalPaidPaise), y + BODY_SIZE, bodyBoldPaint)
                y += ROW_HEIGHT + BLOCK_GAP
            }

            private fun drawBalanceBand() {
                ensureSpace(BAND_HEIGHT + BLOCK_GAP)
                val band = RectF(MARGIN, y, PAGE_WIDTH - MARGIN, y + BAND_HEIGHT)
                canvas.drawRoundRect(band, BAND_RADIUS, BAND_RADIUS, bandFillPaint)
                val baseline = y + BAND_HEIGHT / 2 + SECTION_SIZE / 2 - 2f
                val due = data.duePaise
                val label =
                    if (due > 0) {
                        ctx.getString(R.string.invoice_balance_due)
                    } else {
                        "${ctx.getString(R.string.invoice_balance_due)} · ${ctx.getString(R.string.invoice_fully_paid)}"
                    }
                canvas.drawText(label, MARGIN + BAND_PADDING, baseline, sectionPaint)
                val amountPaint = paint(if (due > 0) COLOR_DUE_RED else COLOR_PAID_GREEN, SECTION_SIZE, bold = true)
                amountPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(AmountFormatter.format(maxOf(due, 0)), PAGE_WIDTH - MARGIN - BAND_PADDING, baseline, amountPaint)
                y += BAND_HEIGHT + BLOCK_GAP
            }

            private fun drawFooter() {
                val text = "${ctx.getString(R.string.invoice_footer)} · ${date(data.issueDate)}"
                canvas.drawText(text, PAGE_WIDTH / 2, PAGE_HEIGHT - MARGIN + FOOTER_SIZE, footerPaint)
            }

            // ------------------------------------------------------------ helpers

            private fun newPage() {
                if (pageCount > 0) document.finishPage(page)
                pageCount++
                page =
                    document.startPage(
                        PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageCount).create(),
                    )
                canvas = page.canvas
                y = MARGIN
            }

            private fun ensureSpace(needed: Float) {
                if (y + needed > PAGE_HEIGHT - MARGIN - FOOTER_RESERVE) newPage()
            }

            private fun line(
                text: String,
                textPaint: Paint,
            ) {
                ensureSpace(ROW_HEIGHT)
                canvas.drawText(text, MARGIN, y + textPaint.textSize, textPaint)
                y += textPaint.textSize + LINE_GAP
            }

            private fun amountRow(
                label: String,
                amountPaise: Long,
                labelPaint: Paint,
                amountPaint: Paint,
            ) {
                ensureSpace(ROW_HEIGHT)
                canvas.drawText(label, MARGIN, y + BODY_SIZE, labelPaint)
                drawRight(AmountFormatter.format(amountPaise), y + BODY_SIZE, amountPaint)
                y += ROW_HEIGHT
            }

            private fun drawRight(
                text: String,
                baseline: Float,
                base: Paint,
            ) {
                val rightAligned = Paint(base).apply { textAlign = Paint.Align.RIGHT }
                canvas.drawText(text, PAGE_WIDTH - MARGIN, baseline, rightAligned)
            }

            private fun date(value: LocalDate): String = dateFormat.format(value)

            private fun loadLogo(path: String?): Bitmap? {
                if (path.isNullOrBlank()) return null
                val file = File(path)
                if (!file.exists()) return null
                return BitmapFactory.decodeFile(file.absolutePath)
            }

            private fun paint(
                color: Int,
                size: Float,
                bold: Boolean = false,
            ): Paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    textSize = size
                    typeface =
                        if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
                }
        }

        private companion object {
            const val INVOICE_DIR = "invoices"
            const val PDF_EXTENSION = ".pdf"

            // Layout contract constants (shared/invoice/layout-spec.md).
            const val PAGE_WIDTH = 595f
            const val PAGE_HEIGHT = 842f
            const val MARGIN = 40f
            const val BODY_SIZE = 11f
            const val SECTION_SIZE = 13f
            const val NAME_SIZE = 20f
            const val FOOTER_SIZE = 9f
            const val LOGO_SIZE = 56f
            const val LOGO_RADIUS = 8f
            const val LINE_GAP = 6f
            const val RULE_GAP = 16f
            const val BLOCK_GAP = 14f
            const val ROW_HEIGHT = 18f
            const val BAND_HEIGHT = 30f
            const val BAND_RADIUS = 8f
            const val BAND_PADDING = 10f
            const val FOOTER_RESERVE = 24f
            const val NOTES_MAX_CHARS = 28
            const val COL_DATE = MARGIN
            const val COL_METHOD = 190f
            const val COL_NOTES = 300f

            val COLOR_ACCENT = Color.parseColor("#6750A4")
            val COLOR_BODY = Color.parseColor("#1C1B1F")
            val COLOR_GREY = Color.parseColor("#49454F")
            val COLOR_TONAL = Color.parseColor("#EADDFF")
            val COLOR_DUE_RED = Color.parseColor("#B3261E")
            val COLOR_PAID_GREEN = Color.parseColor("#146C2E")
        }
    }
