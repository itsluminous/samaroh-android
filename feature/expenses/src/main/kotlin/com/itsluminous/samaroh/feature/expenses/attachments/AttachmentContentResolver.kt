package com.itsluminous.samaroh.feature.expenses.attachments

import android.util.Log
import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.google.drive.DriveFetchResult
import com.itsluminous.samaroh.core.google.drive.DriveFileFetcher
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
 * Resolves the bytes of a tapped expense attachment (ADR-052, member fallback ADR-059)
 * in priority order:
 *
 * 1. live `local_cache_path` file → open directly;
 * 2. `drive_file_id` set → the shared [DriveFileFetcher] ladder (own-token
 *    `files.get?alt=media` when linked, then the anyone-with-link public download) —
 *    on success cache + stamp the row's `local_cache_path` (Room-only) + open;
 * 3. ladder failed → [AttachmentOpenResult.NeedsGoogleLink] when not linked and Drive
 *    answered (the file is simply not link-shared yet — old bill awaiting the
 *    uploader's repair pass), else [AttachmentOpenResult.DownloadFailed] (typically
 *    offline — friendly retry message);
 * 4. no `drive_file_id` at all → [AttachmentOpenResult.NotAvailable].
 *
 * Downloads land in the same app-private dir the compressor writes to, so the existing
 * `expenses_file_paths.xml` FileProvider grant covers them for the PDF `ACTION_VIEW` path.
 */
class AttachmentContentResolver(
    private val attachmentsDir: () -> File,
    private val driveFileFetcher: DriveFileFetcher,
    private val ledgerRepository: ExpensesLedgerRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun resolve(attachment: AttachmentWithLocalState): AttachmentOpenResult {
        val row = attachment.attachment
        val cached = attachment.localCachePath?.let(::File)?.takeIf { it.exists() }
        if (cached != null) return AttachmentOpenResult.Ready(cached, row.mimeType)
        val driveFileId = row.driveFileId ?: return AttachmentOpenResult.NotAvailable
        return withContext(ioDispatcher) {
            // Deterministic per-row name: a re-tap after a crash overwrites the same file
            // instead of piling up partials; the extension travels with `file_name`.
            val target = File(attachmentsDir().apply { mkdirs() }, "drive-${row.id}-${row.fileName}")
            when (val fetched = driveFileFetcher.fetchInto(driveFileId, target)) {
                is DriveFetchResult.Success -> ready(row.id, target, row.mimeType)
                is DriveFetchResult.Failure ->
                    if (!fetched.linked && fetched.error is GoogleApiException) {
                        // Drive answered but withheld the file: it is not link-shared (yet).
                        // Linking is the honest last resort — the user's own uploads resolve
                        // via their token; someone else's await the uploader's repair pass.
                        AttachmentOpenResult.NeedsGoogleLink
                    } else {
                        AttachmentOpenResult.DownloadFailed
                    }
            }
        }
    }

    private suspend fun ready(
        attachmentId: String,
        target: File,
        mimeType: String,
    ): AttachmentOpenResult.Ready {
        ledgerRepository.updateAttachmentLocalCachePath(attachmentId, target.absolutePath)
        Log.i(TAG, "attachment downloaded (id=$attachmentId, bytes=${target.length()})")
        return AttachmentOpenResult.Ready(target, mimeType)
    }

    private companion object {
        const val TAG = "AttachmentResolver"
    }
}
