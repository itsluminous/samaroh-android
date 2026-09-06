package com.itsluminous.samaroh.feature.expenses.attachments

import android.util.Log
import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.drive.DriveService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Where a tapped attachment's bytes can (or cannot) come from — one case per UI outcome. */
sealed interface AttachmentOpenResult {
    /** The file is on this device (cached, or just downloaded) — open it. */
    data class Ready(
        val file: File,
        val mimeType: String,
    ) : AttachmentOpenResult

    /** The file is in Google Drive but no Google account is linked — show the link dialog. */
    data object NeedsGoogleLink : AttachmentOpenResult

    /** No local file and no Drive copy yet (added on another device, upload pending). */
    data object NotAvailable : AttachmentOpenResult

    /** The Drive download failed (typically offline) — show the friendly retry message. */
    data object DownloadFailed : AttachmentOpenResult
}

/**
 * Resolves the bytes of a tapped expense attachment (ADR-052) in priority order:
 *
 * 1. live `local_cache_path` file → open directly;
 * 2. `drive_file_id` set + Google linked → download `files.get?alt=media` into the
 *    attachments dir, stamp the row's `local_cache_path` (Room-only), then open;
 * 3. `drive_file_id` set but NOT linked → [AttachmentOpenResult.NeedsGoogleLink];
 * 4. neither → [AttachmentOpenResult.NotAvailable].
 *
 * Downloads land in the same app-private dir the compressor writes to, so the existing
 * `expenses_file_paths.xml` FileProvider grant covers them for the PDF `ACTION_VIEW` path.
 */
class AttachmentContentResolver(
    private val attachmentsDir: () -> File,
    private val driveService: DriveService,
    private val googleAccountLinker: GoogleAccountLinker,
    private val ledgerRepository: ExpensesLedgerRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun resolve(attachment: AttachmentWithLocalState): AttachmentOpenResult {
        val row = attachment.attachment
        val cached = attachment.localCachePath?.let(::File)?.takeIf { it.exists() }
        if (cached != null) return AttachmentOpenResult.Ready(cached, row.mimeType)
        val driveFileId = row.driveFileId ?: return AttachmentOpenResult.NotAvailable
        if (googleAccountLinker.linkState.first() !is GoogleLinkState.Linked) {
            return AttachmentOpenResult.NeedsGoogleLink
        }
        return withContext(ioDispatcher) {
            // Deterministic per-row name: a re-tap after a crash overwrites the same file
            // instead of piling up partials; the extension travels with `file_name`.
            val target = File(attachmentsDir().apply { mkdirs() }, "drive-${row.id}-${row.fileName}")
            runCatching {
                driveService.downloadFile(driveFileId, target)
                ledgerRepository.updateAttachmentLocalCachePath(row.id, target.absolutePath)
                Log.i(TAG, "attachment downloaded (id=${row.id}, bytes=${target.length()})")
                AttachmentOpenResult.Ready(target, row.mimeType)
            }.getOrElse { error ->
                Log.w(TAG, "attachment download failed (id=${row.id})", error)
                target.delete()
                AttachmentOpenResult.DownloadFailed
            }
        }
    }

    private companion object {
        const val TAG = "AttachmentResolver"
    }
}
