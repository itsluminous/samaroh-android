package com.itsluminous.samaroh.feature.expenses.attachments

import android.util.Log
import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.drive.DriveService
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
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
 * Resolves the bytes of a tapped expense attachment (ADR-052, member fallback ADR-059)
 * in priority order:
 *
 * 1. live `local_cache_path` file → open directly;
 * 2. `drive_file_id` set + Google linked → download `files.get?alt=media` with the
 *    current user's token, cache, stamp the row's `local_cache_path` (Room-only), open;
 * 3. `drive_file_id` set and the own-token path failed OR no account is linked →
 *    PUBLIC LINK fallback (`uc?export=download`, no credentials): bills are shared
 *    anyone-with-link at upload (ADR-059), so a business member whose token cannot read
 *    another account's file still gets the bytes. Cache + stamp + open as usual;
 * 4. public path also failed → [AttachmentOpenResult.NeedsGoogleLink] when not linked
 *    and Drive answered (the file is simply not link-shared yet — old bill awaiting the
 *    uploader's repair pass), else [AttachmentOpenResult.DownloadFailed] (typically
 *    offline — friendly retry message);
 * 5. no `drive_file_id` at all → [AttachmentOpenResult.NotAvailable].
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
        val linked = googleAccountLinker.linkState.first() is GoogleLinkState.Linked
        return withContext(ioDispatcher) {
            // Deterministic per-row name: a re-tap after a crash overwrites the same file
            // instead of piling up partials; the extension travels with `file_name`.
            val target = File(attachmentsDir().apply { mkdirs() }, "drive-${row.id}-${row.fileName}")
            if (linked) {
                val own = runCatching { driveService.downloadFile(driveFileId, target) }
                if (own.isSuccess) return@withContext ready(row.id, target, row.mimeType)
                Log.i(TAG, "own-token download failed (id=${row.id}) — trying public link", own.exceptionOrNull())
            }
            // ADR-059 member fallback: anyone-with-link files download without credentials.
            runCatching {
                driveService.downloadPublicFile(driveFileId, target)
                ready(row.id, target, row.mimeType)
            }.getOrElse { error ->
                Log.w(TAG, "public-link download failed (id=${row.id})", error)
                target.delete()
                if (!linked && error is GoogleApiException) {
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
