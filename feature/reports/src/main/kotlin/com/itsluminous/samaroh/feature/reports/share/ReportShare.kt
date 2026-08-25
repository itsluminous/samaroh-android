package com.itsluminous.samaroh.feature.reports.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.reports.export.ExportedReport
import java.io.File

/** Opens the system share sheet for an exported report file (§4.4 export → share/Drive). */
object ReportShare {
    fun share(
        context: Context,
        report: ExportedReport,
    ) {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.reports.fileprovider",
                File(report.absolutePath),
            )
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = report.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.common_action_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
