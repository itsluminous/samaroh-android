package com.itsluminous.samaroh.core.google.drive

import android.util.Log
import com.itsluminous.samaroh.core.data.sync.AttachmentPermissionRepair
import com.itsluminous.samaroh.core.database.dao.ExpenseAttachmentDao
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AttachmentPermissionRepair] over [DriveService.ensureAnyoneReaderPermission] (ADR-059):
 * retroactively shares Drive-hosted bills link-scoped so business members can view them.
 * Bills uploaded before ADR-059 (and any upload whose inline permission call failed) have
 * `drive_file_id` set but the device-only `drive_permission_ensured` flag unset.
 *
 * Per sync run, at most [MAX_REPAIRS_PER_RUN] rows are attempted (same throttle spirit as
 * the ADR-058 mirror — syncs fire constantly, so the backlog drains across runs without
 * hammering the Drive API). Outcomes per row:
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
        private val driveService: DriveService,
    ) : AttachmentPermissionRepair {
        override suspend fun repairPending(): Int {
            val pending = attachmentDao.pendingPermissionRepair(MAX_REPAIRS_PER_RUN)
            var settled = 0
            for (row in pending) {
                val fileId = row.driveFileId ?: continue
                val result = runCatching { driveService.ensureAnyoneReaderPermission(fileId) }
                result.fold(
                    onSuccess = {
                        attachmentDao.markDrivePermissionEnsured(row.id)
                        settled++
                        Log.i(TAG, "link permission ensured for bill ${row.id} (drive=$fileId)")
                    },
                    onFailure = { error ->
                        when {
                            error is DriveNotAvailableException -> return settled
                            error is GoogleApiException && error.code in NOT_MY_FILE_CODES -> {
                                // Definitive: this account cannot see/share the file. Stop
                                // retrying from THIS device; the uploader's device repairs it.
                                attachmentDao.markDrivePermissionEnsured(row.id)
                                settled++
                                Log.i(TAG, "bill ${row.id} not repairable from this device (http ${error.code})")
                            }
                            else -> Log.w(TAG, "permission repair failed for ${row.id}: ${error.message}")
                        }
                    },
                )
            }
            return settled
        }

        companion object {
            const val TAG = "SamarohDriveRepair"

            /** Repair budget per sync run (ADR-059) — one cheap POST each, but bounded anyway. */
            const val MAX_REPAIRS_PER_RUN = 10

            /** The current token cannot read (404) or share (403) the file — another account owns it. */
            private val NOT_MY_FILE_CODES = setOf(403, 404)
        }
    }
