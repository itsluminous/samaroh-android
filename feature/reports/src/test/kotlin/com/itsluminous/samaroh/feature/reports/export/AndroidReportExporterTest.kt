package com.itsluminous.samaroh.feature.reports.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowPdfDocument::class])
class AndroidReportExporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Clock.fixed(Instant.parse("2026-08-25T09:00:00Z"), ZoneOffset.UTC)
    private val exporter = AndroidReportExporter(context, clock)

    private val table =
        ReportTable(
            title = "fixture-title",
            subtitle = "fixture-subtitle",
            columns = listOf("col-a", "col-b"),
            rows = (1..60).map { listOf("row-$it", "value-$it") },
        )

    @Test
    fun `csv export writes the serialized table to app storage`() =
        runTest {
            val exported = exporter.export("revenue", table, ReportExportFormat.CSV).getOrThrow()

            assertThat(exported.mimeType).isEqualTo("text/csv")
            val file = File(exported.absolutePath)
            assertThat(file.exists()).isTrue()
            assertThat(file.readText()).isEqualTo(ReportCsvBuilder.build(table))
            // The stamp renders in the device zone; assert the shape, not the exact time.
            assertThat(file.name).matches("revenue-\\d{8}-\\d{6}\\.csv")
        }

    @Test
    fun `pdf export renders a non-empty document, paginating long tables`() =
        runTest {
            val exported = exporter.export("dues_aging", table, ReportExportFormat.PDF).getOrThrow()

            assertThat(exported.mimeType).isEqualTo("application/pdf")
            val file = File(exported.absolutePath)
            assertThat(file.exists()).isTrue()
            assertThat(file.length()).isGreaterThan(0L)
            val content = file.readText(Charsets.US_ASCII)
            assertThat(content).startsWith("%PDF")
            // 60 rows do not fit one A4 page at 18 pt per row → the renderer must paginate.
            assertThat(content.split("% page ").size - 1).isAtLeast(2)
        }
}
