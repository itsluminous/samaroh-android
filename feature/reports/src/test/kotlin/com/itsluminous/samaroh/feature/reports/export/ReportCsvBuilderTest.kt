package com.itsluminous.samaroh.feature.reports.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReportCsvBuilderTest {
    @Test
    fun `serializes header and rows with CRLF and a BOM`() {
        val table =
            ReportTable(
                title = "t",
                subtitle = "s",
                columns = listOf("col-a", "col-b"),
                rows = listOf(listOf("1", "2"), listOf("3", "4")),
            )

        val csv = ReportCsvBuilder.build(table)

        assertThat(csv).isEqualTo("\uFEFFcol-a,col-b\r\n1,2\r\n3,4\r\n")
    }

    @Test
    fun `quotes fields containing commas and line breaks`() {
        assertThat(ReportCsvBuilder.escape("a,b")).isEqualTo("\"a,b\"")
        assertThat(ReportCsvBuilder.escape("line1\nline2")).isEqualTo("\"line1\nline2\"")
        assertThat(ReportCsvBuilder.escape("plain")).isEqualTo("plain")
    }

    @Test
    fun `doubles embedded quotes`() {
        assertThat(ReportCsvBuilder.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
    }

    @Test
    fun `currency and non-Latin text pass through unquoted`() {
        val table =
            ReportTable(
                title = "t",
                subtitle = "s",
                columns = listOf("महीना", "राशि"),
                rows = listOf(listOf("अगस्त 2026", "₹1,06,511")),
            )

        val csv = ReportCsvBuilder.build(table)

        // ₹ grouping uses commas → the amount field must be quoted; Hindi text must not be mangled.
        assertThat(csv).contains("महीना,राशि\r\n")
        assertThat(csv).contains("अगस्त 2026,\"₹1,06,511\"\r\n")
    }
}
