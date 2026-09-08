package com.itsluminous.samaroh.core.google.drive

import android.util.Log
import com.itsluminous.samaroh.core.data.sync.AttachmentPermissionRepair
import com.itsluminous.samaroh.core.database.dao.ExpenseAttachmentDao
import com.itsluminous.samaroh.core.database.dao.MasterItemDao
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AttachmentPermissionRepair] over [DriveService.ensureAnyoneReaderPermission] (ADR-059,
 * item photos added by ADR-063, kept as fresh-user resilience by ADR-065): shares
 * Drive-hosted media link-scoped so business members (and the web app) can view them.
 * Every upload tries the permission inline; this pass is the retry when that inline
 * call failed. Two pending sets, same shape:
 *
 * - `expense_attachments` rows with a `drive_file_id` whose device-only
 *   `drive_permission_ensured` flag is unset;
 * - `master_items` rows with a `drive_image_id` whose device-only twin flag is unset.
 *
 * Per sync run, at most [MAX_REPAIRS_PER_RUN] rows are attempted PER SET (same throttle
 * spirit as the ADR-058 mirror — syncs fire constantly, so the backlog drains across
 * runs without hammering the Drive API). Outcomes per row:
 *
 * - success → mark ensured (this device fixed it);
 * - 403/404 → mark ensured LOCALLY: the file belongs to another member's account, so
 *   this device can never share it — the flag never syncs, so the uploader's own device
 *   keeps its row pending and repairs the file itself;
 * - not linked → stop the whole pass silently (everything stays pending);
 * - anything else (offline, quota) → leave pending for the next run.
 */
@Singleton
class DriveAttachmentPermissionRepair
    @Inject
    constructor(
        private val attachmentDao: ExpenseAttachmentDao,
        private val masterItemDao: MasterItemDao,
        private val driveService: DriveService,
    ) : AttachmentPermissionRepair {
        override suspend fun repairPending(): Int {
            var settled = 0
            for (row in attachmentDao.pendingPermissionRepair(MAX_REPAIRS_PER_RUN)) {
                val fileId = row.driveFileId ?: continue
                when (repairOne("bill", row.id, fileId, attachmentDao::markDrivePermissionEnsured)) {
                    RowOutcome.SETTLED -> settled++
                    RowOutcome.STOP_PASS -> return settled
                    RowOutcome.RETRY_LATER -> Unit
                }
            }
            for (row in masterItemDao.pendingDrivePermissionRepair(MAX_REPAIRS_PER_RUN)) {
                val fileId = row.driveImageId ?: continue
                when (repairOne("item photo", row.id, fileId, masterItemDao::markDrivePermissionEnsured)) {
                    RowOutcome.SETTLED -> settled++
                    RowOutcome.STOP_PASS -> return settled
                    RowOutcome.RETRY_LATER -> Unit
                }
            }
            return settled
        }

        private suspend fun repairOne(
            kind: String,
            rowId: String,
            fileId: String,
            markEnsured: suspend (String) -> Unit,
        ): RowOutcome {
            val result = runCatching { driveService.ensureAnyoneReaderPermission(fileId) }
            return result.fold(
                onSuccess = {
                    markEnsured(rowId)
                    Log.i(TAG, "link permission ensured for $kind $rowId (drive=$fileId)")
                    RowOutcome.SETTLED
                },
                onFailure = { error ->
                    when {
                        error is DriveNotAvailableException -> RowOutcome.STOP_PASS
                        error is GoogleApiException && error.code in NOT_MY_FILE_CODES -> {
                            // Definitive: this account cannot see/share the file. Stop
                            // retrying from THIS device; the uploader's device repairs it.
                            markEnsured(rowId)
                            Log.i(TAG, "$kind $rowId not repairable from this device (http ${error.code})")
                            RowOutcome.SETTLED
                        }
                        else -> {
                            Log.w(TAG, "permission repair failed for $kind $rowId: ${error.message}")
                            RowOutcome.RETRY_LATER
                        }
                    }
                },
            )
        }

        private enum class RowOutcome { SETTLED, STOP_PASS, RETRY_LATER }

        companion object {
            const val TAG = "SamarohDriveRepair"

            /** Repair budget per set per sync run (ADR-059) — one cheap POST each, bounded anyway. */
            const val MAX_REPAIRS_PER_RUN = 10

            /** The current token cannot read (404) or share (403) the file — another account owns it. */
            private val NOT_MY_FILE_CODES = setOf(403, 404)
        }
    }
