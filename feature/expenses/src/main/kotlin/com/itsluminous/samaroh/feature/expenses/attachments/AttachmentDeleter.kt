package com.itsluminous.samaroh.feature.expenses.attachments

import android.util.Log
import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.data.repository.CascadeDeletedAttachment
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.drive.DriveService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Deletes expense attachments — one from the viewer (ADR-053), or the remains of an
 * entry/party cascade (ADR-063) — children of the metadata tombstone first-class:
 *
 * 1. tombstone the `expense_attachments` row + outbox DELETE (authoritative — every
 *    device converges on the removal via sync);
 * 2. remove the on-device cached file, if any;
 * 3. best-effort Drive `files.delete` when the file was uploaded (`drive.file` scope
 *    covers app-created files). A Drive failure (offline, revoked) is NON-FATAL and only
 *    logged: the metadata tombstone wins, the orphaned Drive file merely lingers in the
 *    owner's Drive folder.
 */
class AttachmentDeleter(
    private val ledgerRepository: ExpensesLedgerRepository,
    private val driveService: DriveService,
    private val googleAccountLinker: GoogleAccountLinker,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun delete(attachment: AttachmentWithLocalState) {
        val row = attachment.attachment
        ledgerRepository.deleteAttachment(row.id)
        withContext(ioDispatcher) {
            attachment.localCachePath?.let { path -> runCatching { File(path).delete() } }
            val driveFileId = row.driveFileId ?: return@withContext
            if (googleAccountLinker.linkState.first() !is GoogleLinkState.Linked) {
                Log.i(TAG, "attachment deleted locally; drive copy left (not linked, id=${row.id})")
                return@withContext
            }
            runCatching { driveService.deleteFile(driveFileId) }
                .onSuccess { Log.i(TAG, "drive copy deleted (id=${row.id})") }
                .onFailure { error ->
                    Log.w(TAG, "best-effort drive delete failed (id=${row.id}) — tombstone wins", error)
                }
        }
    }

    /**
     * Device-side cleanup after a cascade delete (entry tombstone or party cascade,
     * ADR-063): removes local cache files and best-effort deletes the Drive copies —
     * the same non-fatal semantics as [delete], per attachment. Rows are already
     * tombstoned by the repository; nothing here can fail the deletion.
     */
    suspend fun cleanUpCascade(attachments: List<CascadeDeletedAttachment>) {
        if (attachments.isEmpty()) return
        withContext(ioDispatcher) {
            attachments.forEach { it.localCachePath?.let { path -> runCatching { File(path).delete() } } }
            val withDrive = attachments.filter { it.driveFileId != null }
            if (withDrive.isEmpty()) return@withContext
            if (googleAccountLinker.linkState.first() !is GoogleLinkState.Linked) {
                Log.i(TAG, "cascade deleted ${withDrive.size} attachment(s); drive copies left (not linked)")
                return@withContext
            }
            withDrive.forEach { deleted ->
                runCatching { driveService.deleteFile(checkNotNull(deleted.driveFileId)) }
                    .onSuccess { Log.i(TAG, "drive copy deleted (id=${deleted.attachmentId})") }
                    .onFailure { error ->
                        Log.w(TAG, "best-effort drive delete failed (id=${deleted.attachmentId}) — tombstone wins", error)
                    }
            }
        }
    }

    private companion object {
        const val TAG = "AttachmentDeleter"
    }
}
