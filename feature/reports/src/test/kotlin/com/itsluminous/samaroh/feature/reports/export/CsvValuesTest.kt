package com.itsluminous.samaroh.feature.reports.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * ADR-027: CSV exports carry machine-readable values — plain decimal-rupee amounts
 * (no ₹, no grouping commas) and ISO dates — so spreadsheet apps parse them.
 */
class CsvValuesTest {
    @Test
    fun `rupees renders plain two-decimal numbers with no symbol or grouping`() {
        assertThat(CsvValues.rupees(10_651_161L)).isEqualTo("106511.61")
        assertThat(CsvValues.rupees(500_00L)).isEqualTo("500.00")
        assertThat(CsvValues.rupees(5L)).isEqualTo("0.05")
        assertThat(CsvValues.rupees(0L)).isEqualTo("0.00")
    }

    @Test
    fun `negative amounts keep a plain leading minus`() {
        assertThat(CsvValues.rupees(-50L)).isEqualTo("-0.50")
        assertThat(CsvValues.rupees(-1_234_56L)).isEqualTo("-1234.56")
    }

    @Test
    fun `very large amounts never use scientific notation or grouping`() {
        // numeric(12,2) ceiling: 9,999,999,999.99 rupees = 12 digits of paise.
        assertThat(CsvValues.rupees(999_999_999_999L)).isEqualTo("9999999999.99")
    }

    @Test
    fun `dates and months are ISO`() {
        assertThat(CsvValues.date(LocalDate.of(2026, 8, 27))).isEqualTo("2026-08-27")
        assertThat(CsvValues.month(YearMonth.of(2026, 8))).isEqualTo("2026-08")
    }

    @Test
    fun `csv cells never need quoting as numbers`() {
        // No commas in plain values → Excel/Sheets read them as numeric columns.
        assertThat(ReportCsvBuilder.escape(CsvValues.rupees(10_651_161L))).isEqualTo("106511.61")
    }
}

class CsvExportRowsTest {
    @Test
    fun `csv uses csvRows and csvTotalRow instead of the display rows`() {
        val table =
            ReportTable(
                title = "t",
                subtitle = "s",
                columns = listOf("Month", "Amount"),
                rows = listOf(listOf("Aug 2026", "₹1,06,511.61")),
                totalRow = listOf("Total", "₹1,06,511.61"),
                csvRows = listOf(listOf("2026-08", "106511.61")),
                csvTotalRow = listOf("Total", "106511.61"),
            )

        val csv = ReportCsvBuilder.build(table)

        assertThat(csv).isEqualTo("\uFEFFMonth,Amount\r\n2026-08,106511.61\r\nTotal,106511.61\r\n")
        assertThat(csv).doesNotContain("₹")
    }

    @Test
    fun `total row falls back to the display total when no csv variant is set`() {
        val table =
            ReportTable(
                title = "t",
                subtitle = "s",
                columns = listOf("a"),
                rows = listOf(listOf("1")),
                totalRow = listOf("2"),
            )

        assertThat(ReportCsvBuilder.build(table)).isEqualTo("\uFEFFa\r\n1\r\n2\r\n")
    }

    @Test
    fun `tables without a total row export unchanged`() {
        val table =
            ReportTable(
                title = "t",
                subtitle = "s",
                columns = listOf("a", "b"),
                rows = listOf(listOf("1", "2")),
            )

        assertThat(ReportCsvBuilder.build(table)).isEqualTo("\uFEFFa,b\r\n1,2\r\n")
    }
}
