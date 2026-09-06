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

/**
 * Deletes one expense attachment from the viewer (ADR-053), children of the metadata
 * tombstone first-class:
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

    private companion object {
        const val TAG = "AttachmentDeleter"
    }
}
