package com.itsluminous.samaroh.core.google.drive

import com.itsluminous.samaroh.core.data.attachments.AttachmentUploadQueue
import com.itsluminous.samaroh.core.data.sync.AttachmentUploader
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.database.dao.BusinessDao
import com.itsluminous.samaroh.core.database.dao.ExpenseAttachmentDao
import com.itsluminous.samaroh.core.database.dao.ExpenseDao
import com.itsluminous.samaroh.core.database.dao.PartyDao
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Wave-1 unification of the two attachment seams (docs/decisions.md ADR-018):
 *
 * - `core:data`'s `AttachmentUploadQueue` (W1-B, called by the expenses feature after it
 *   persisted the metadata row + outbox upsert) and
 * - `core:data`'s `AttachmentUploader` (W1-E, called by the sync engine while draining
 *   the outbox, upload-before-row-push per §8)
 *
 * genuinely overlap: both mean "get this expense attachment into Drive and stamp
 * `drive_file_id`". One Drive pipeline serves both: [DriveAttachmentUploader] does the
 * actual upload during the outbox drain (the engine patches the payload, applies it
 * locally and pushes the row — §8 ordering); [DriveBackedAttachmentUploadQueue] is the
 * cheap enqueue side, which only has to nudge the scheduler because the metadata row is
 * already in the outbox when `enqueue` is called (see the queue contract's KDoc).
 */

/** Uploads one expense attachment to `Samaroh/{business}/invoices/expenses/{party}/…` (§9.1). */
@Singleton
class DriveAttachmentUploader
    @Inject
    constructor(
        private val attachmentDao: ExpenseAttachmentDao,
        private val expenseDao: ExpenseDao,
        private val partyDao: PartyDao,
        private val businessDao: BusinessDao,
        private val driveUploader: DriveUploader,
    ) : AttachmentUploader {
        override suspend fun upload(attachmentId: String): AttachmentUploader.UploadResult {
            val row =
                attachmentDao.byId(attachmentId)
                    ?: return AttachmentUploader.UploadResult.Failed("attachment row $attachmentId not found", retriable = false)
            row.driveFileId?.let { return AttachmentUploader.UploadResult.Uploaded(it) }
            val localPath =
                row.localCachePath
                    ?: return AttachmentUploader.UploadResult.Failed("attachment $attachmentId has no local file", retriable = false)
            val file = File(localPath)
            if (!file.exists()) {
                return AttachmentUploader.UploadResult.Failed("attachment file missing: $localPath", retriable = false)
            }
            val business =
                businessDao.byId(row.businessId)
                    ?: return AttachmentUploader.UploadResult.Failed("business ${row.businessId} not found", retriable = false)
            val partyName =
                expenseDao
                    .byId(row.expenseId)
                    ?.partyId
                    ?.let { partyDao.byId(it)?.name }
                    .orEmpty()

            return driveUploader
                .upload(
                    businessName = business.name,
                    target = DriveTarget.ExpenseInvoices(partyName),
                    fileName = row.fileName,
                    mimeType = row.mimeType,
                    sourceFile = file,
                ).fold(
                    onSuccess = { AttachmentUploader.UploadResult.Uploaded(it.fileId) },
                    onFailure = { error ->
                        when (error) {
                            is DriveNotAvailableException -> AttachmentUploader.UploadResult.NotLinked
                            // Network-ish failures stay queued with a visible pending state (§4.2).
                            else ->
                                AttachmentUploader.UploadResult.Failed(
                                    error.message ?: "drive upload failed",
                                    retriable = true,
                                )
                        }
                    },
                )
        }
    }

/**
 * Drive-backed [AttachmentUploadQueue] (§2/§4.2 — Drive is the authoritative store for
 * attachment bytes). By the queue contract, callers persist the metadata row (with its
 * outbox upsert) BEFORE calling [enqueue]; the actual upload runs inside the sync drain
 * via [DriveAttachmentUploader], so enqueueing is just a cheap, idempotent sync nudge.
 * Safe offline and before a Google account is linked: the op simply stays queued with the
 * pending badge until an upload succeeds.
 */
@Singleton
class DriveBackedAttachmentUploadQueue
    @Inject
    constructor(
        private val attachmentDao: ExpenseAttachmentDao,
        private val syncScheduler: SyncScheduler,
    ) : AttachmentUploadQueue {
        override suspend fun enqueue(
            localPath: String,
            expenseId: String,
        ) {
            // Resolve the metadata row per the contract (local_cache_path scoped to the
            // expense). A missing row means the caller broke the persist-first contract —
            // stay silent (the sync pass is harmless) rather than crash the save flow.
            val pending =
                attachmentDao
                    .attachmentsForExpense(expenseId)
                    .first()
                    .any { it.localCachePath == localPath && it.driveFileId == null }
            if (pending) syncScheduler.requestImmediateSync()
        }
    }
