package com.itsluminous.samaroh.feature.reports.export

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** A report file written to app-private storage, ready for the share sheet. */
data class ExportedReport(
    val absolutePath: String,
    val mimeType: String,
)

/** Writes a [ReportTable] to a shareable PDF or CSV file; interface so tests can fake it. */
interface ReportExporter {
    suspend fun export(
        fileBaseName: String,
        table: ReportTable,
        format: ReportExportFormat,
    ): Result<ExportedReport>
}

@Singleton
class AndroidReportExporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val clock: Clock,
    ) : ReportExporter {
        override suspend fun export(
            fileBaseName: String,
            table: ReportTable,
            format: ReportExportFormat,
        ): Result<ExportedReport> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val outDir = File(context.filesDir, REPORTS_DIR).apply { mkdirs() }
                    val stamp =
                        LocalDateTime
                            .ofInstant(clock.instant(), ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    when (format) {
                        ReportExportFormat.CSV -> {
                            val file = File(outDir, "$fileBaseName-$stamp.csv")
                            file.writeText(ReportCsvBuilder.build(table))
                            ExportedReport(file.absolutePath, MIME_CSV)
                        }
                        ReportExportFormat.PDF -> {
                            val file = File(outDir, "$fileBaseName-$stamp.pdf")
                            ReportPdfRenderer(context).render(table, file)
                            ExportedReport(file.absolutePath, MIME_PDF)
                        }
                    }
                }
            }

        private companion object {
            const val REPORTS_DIR = "reports"
            const val MIME_CSV = "text/csv"
            const val MIME_PDF = "application/pdf"
        }
    }
