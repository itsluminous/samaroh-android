package com.itsluminous.samaroh.core.data.attachments

/**
 * Queue for uploading expense-invoice attachment files to Google Drive — Drive is the
 * AUTHORITATIVE store for these files (§2, §4.2): the bytes never touch Supabase; only the
 * `expense_attachments` metadata row syncs, with `drive_file_id` null while the upload is
 * pending.
 *
 * Contract (additive Wave-1 seam; docs/decisions.md ADR-007 — same pattern as
 * [com.itsluminous.samaroh.core.data.sync.OutboxWriter]):
 *
 * - Callers first persist the metadata row (`drive_file_id = null`,
 *   `local_cache_path = localPath`) and then call [enqueue]. The pending state IS the
 *   metadata row: UI renders a pending badge for any attachment with a null
 *   `drive_file_id`.
 * - The implementation (delivered by `core:google`, W1-F) uploads the file at [localPath]
 *   to `Samaroh/{Business}/invoices/expenses/…` (§9.1), resolves the metadata row via
 *   `expense_attachments.local_cache_path == localPath` scoped to [expenseId], sets its
 *   `drive_file_id`, and enqueues the updated row for sync.
 * - [enqueue] must be cheap and non-blocking (persist intent only); uploads happen in
 *   background work. It must be safe to call offline and before a Google account is
 *   linked — the file simply stays queued (visible as pending) until upload succeeds.
 * - Re-enqueueing the same (localPath, expenseId) pair is idempotent.
 */
interface AttachmentUploadQueue {
    /**
     * Queues the file at [localPath] (app-private absolute path) for Google Drive upload,
     * on behalf of the expense row [expenseId].
     */
    suspend fun enqueue(
        localPath: String,
        expenseId: String,
    )
}

/**
 * Wave-1 placeholder, RETIRED at integration: `core:google` binds the Drive-backed
 * implementation (docs/decisions.md ADR-018). Kept only for tests that need a no-op
 * queue; no production binding refers to it anymore.
 */
@Deprecated("Superseded by the Drive-backed queue in core:google (ADR-018).")
class LocalOnlyAttachmentUploadQueue
    @javax.inject.Inject
    constructor() : AttachmentUploadQueue {
        override suspend fun enqueue(
            localPath: String,
            expenseId: String,
        ) {
            // Intentionally empty: the persisted metadata row (driveFileId == null) already
            // records the pending upload; the Drive worker replaces this binding.
        }
    }
