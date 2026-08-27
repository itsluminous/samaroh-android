package com.itsluminous.samaroh.feature.reports.export

/**
 * Fully localized, presentation-ready snapshot of one report: what the on-screen table
 * shows and what both exporters (CSV + PDF) serialize. Cells are pre-formatted strings —
 * money already went through `AmountFormatter`, dates through localized formatters.
 *
 * CSV divergence (ADR-027): [csvRows]/[csvTotalRow], when set, replace [rows]/[totalRow]
 * in the CSV export only — amounts as plain unformatted decimal rupees (no ₹, no
 * grouping) and dates in ISO form, so spreadsheet apps parse them as numbers/dates.
 * On-screen and PDF keep the localized [rows]/[totalRow].
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
    /**
     * Final TOTAL row of a money table (ADR-027), rendered emphasized on screen and in
     * the PDF and appended to the CSV; null for non-money tables.
     */
    val totalRow: List<String>? = null,
    /** Machine-readable CSV replacement for [rows]; null = export [rows] as-is. */
    val csvRows: List<List<String>>? = null,
    /** Machine-readable CSV replacement for [totalRow]; null = export [totalRow] as-is. */
    val csvTotalRow: List<String>? = null,
)

/** Export target formats of every report (§4.4). */
enum class ReportExportFormat {
    PDF,
    CSV,
}

/**
 * Pure CSV serialization (RFC 4180): CRLF line ends, quoting only where needed, and a
 * UTF-8 BOM so spreadsheet apps detect the encoding (Hindi header text).
 */
object ReportCsvBuilder {
    private const val BOM = "\uFEFF"
    private const val CRLF = "\r\n"

    fun build(table: ReportTable): String {
        val lines = mutableListOf<String>()
        lines += table.columns.joinToString(",") { escape(it) }
        (table.csvRows ?: table.rows).forEach { row ->
            lines += row.joinToString(",") { escape(it) }
        }
        (table.csvTotalRow ?: table.totalRow)?.let { totalRow ->
            lines += totalRow.joinToString(",") { escape(it) }
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
