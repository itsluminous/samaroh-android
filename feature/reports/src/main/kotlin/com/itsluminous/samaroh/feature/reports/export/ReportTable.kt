package com.itsluminous.samaroh.feature.reports.export

/**
 * Fully localized, presentation-ready snapshot of one report: what the on-screen table
 * shows and what both exporters (CSV + PDF) serialize. Cells are pre-formatted strings —
 * money already went through `AmountFormatter`, dates through localized formatters.
 */
data class ReportTable(
    /** Localized report name — the PDF heading and the exported file's display title. */
    val title: String,
    /** Localized date-range line (empty for snapshot reports without a range). */
    val subtitle: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    /**
     * Relative column widths for the PDF/on-screen grid, one per column; defaults to
     * equal widths when empty.
     */
    val columnWeights: List<Float> = emptyList(),
)

/** Export target formats of every report (§4.4). */
enum class ReportExportFormat {
    PDF,
    CSV,
}

/**
 * Pure CSV serialization (RFC 4180): CRLF line ends, quoting only where needed, and a
 * UTF-8 BOM so spreadsheet apps detect the encoding (₹ signs, Hindi text).
 */
object ReportCsvBuilder {
    private const val BOM = "\uFEFF"
    private const val CRLF = "\r\n"

    fun build(table: ReportTable): String {
        val lines = mutableListOf<String>()
        lines += table.columns.joinToString(",") { escape(it) }
        table.rows.forEach { row ->
            lines += row.joinToString(",") { escape(it) }
        }
        return BOM + lines.joinToString(CRLF) + CRLF
    }

    /** Quotes a field when it contains a comma, quote or line break; doubles inner quotes. */
    fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
}
