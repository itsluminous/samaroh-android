package com.itsluminous.samaroh.feature.reports.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.itsluminous.samaroh.core.i18n.R
import java.io.File
import java.io.FileOutputStream

/**
 * Android `PdfDocument` renderer for exported reports: A4 portrait, 40 pt margins,
 * localized title + date-range subtitle, a ruled table that repeats its header row on
 * every page, and a centered footer. Same rendering approach as the invoice PDF
 * (`core:invoice`); all cell text arrives pre-localized in the [ReportTable].
 */
internal class ReportPdfRenderer(
    private val context: Context,
) {
    fun render(
        table: ReportTable,
        outFile: File,
    ) {
        val document = PdfDocument()
        try {
            Painter(context, document, table).paint()
            FileOutputStream(outFile).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    private class Painter(
        private val context: Context,
        private val document: PdfDocument,
        private val table: ReportTable,
    ) {
        private val titlePaint = paint(COLOR_BODY, TITLE_SIZE, bold = true)
        private val subtitlePaint = paint(COLOR_GREY, BODY_SIZE)
        private val headerPaint = paint(COLOR_BODY, BODY_SIZE, bold = true)
        private val bodyPaint = paint(COLOR_BODY, BODY_SIZE)
        private val footerPaint = paint(COLOR_GREY, FOOTER_SIZE).apply { textAlign = Paint.Align.CENTER }
        private val rulePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_ACCENT
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }

        private var pageCount = 0
        private lateinit var page: PdfDocument.Page
        private lateinit var canvas: Canvas
        private var y = 0f

        private val weights: List<Float> =
            if (table.columnWeights.size == table.columns.size && table.columnWeights.isNotEmpty()) {
                table.columnWeights
            } else {
                List(table.columns.size) { 1f }
            }

        fun paint() {
            newPage()
            drawTitle()
            drawHeaderRow()
            table.rows.forEach { drawRow(it) }
            finishPage()
        }

        private fun drawTitle() {
            canvas.drawText(table.title, MARGIN, y + TITLE_SIZE, titlePaint)
            y += TITLE_SIZE + 6f
            if (table.subtitle.isNotEmpty()) {
                canvas.drawText(table.subtitle, MARGIN, y + BODY_SIZE, subtitlePaint)
                y += BODY_SIZE + 6f
            }
            y += 8f
        }

        private fun drawHeaderRow() {
            drawCells(table.columns, headerPaint)
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rulePaint)
            y += RULE_GAP
        }

        private fun drawRow(cells: List<String>) {
            if (y + ROW_HEIGHT > PAGE_HEIGHT - MARGIN - FOOTER_SIZE - 12f) {
                finishPage()
                newPage()
                drawHeaderRow()
            }
            drawCells(cells, bodyPaint)
        }

        private fun drawCells(
            cells: List<String>,
            cellPaint: Paint,
        ) {
            val contentWidth = PAGE_WIDTH - 2 * MARGIN
            val totalWeight = weights.sum()
            var x = MARGIN
            cells.forEachIndexed { index, cell ->
                val width = contentWidth * (weights.getOrElse(index) { 1f } / totalWeight)
                canvas.drawText(ellipsize(cell, cellPaint, width - CELL_GAP), x, y + BODY_SIZE, cellPaint)
                x += width
            }
            y += ROW_HEIGHT
        }

        private fun ellipsize(
            text: String,
            cellPaint: Paint,
            maxWidth: Float,
        ): String {
            if (cellPaint.measureText(text) <= maxWidth) return text
            var end = text.length
            while (end > 0 && cellPaint.measureText(text, 0, end) + cellPaint.measureText(ELLIPSIS) > maxWidth) {
                end--
            }
            return text.take(end) + ELLIPSIS
        }

        private fun newPage() {
            pageCount++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageCount).create())
            canvas = page.canvas
            y = MARGIN
        }

        private fun finishPage() {
            canvas.drawText(
                context.getString(R.string.reports_pdf_footer),
                PAGE_WIDTH / 2f,
                PAGE_HEIGHT - MARGIN / 2f,
                footerPaint,
            )
            document.finishPage(page)
        }

        private fun paint(
            color: Int,
            textSize: Float,
            bold: Boolean = false,
        ): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                this.textSize = textSize
                typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            }

        private companion object {
            // A4 portrait in PostScript points.
            const val PAGE_WIDTH = 595f
            const val PAGE_HEIGHT = 842f
            const val MARGIN = 40f
            const val TITLE_SIZE = 16f
            const val BODY_SIZE = 10f
            const val FOOTER_SIZE = 9f
            const val ROW_HEIGHT = 18f
            const val RULE_GAP = 6f
            const val CELL_GAP = 8f
            const val ELLIPSIS = "…"
            val COLOR_BODY = Color.rgb(28, 27, 31)
            val COLOR_GREY = Color.rgb(121, 116, 126)
            val COLOR_ACCENT = Color.rgb(103, 80, 164)
        }
    }
}
