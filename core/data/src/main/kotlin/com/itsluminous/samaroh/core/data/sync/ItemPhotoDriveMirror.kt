package com.itsluminous.samaroh.core.data.sync

/**
 * Uploads inventory item photos to Google Drive — THE image store since ADR-063 (§9.1
 * layout `Samaroh/{Business}/images/inventory/…`): members, other devices and the web
 * app all serve item photos from Drive, exactly like expense bills (ADR-059). The
 * legacy Supabase Storage serving path (ADR-023) is retired — the bucket is gone.
 *
 * Contract (additive seam, same shape as [AttachmentUploader]):
 *
 * - Bound by `core:google`; `core:sync` consumes it as `Optional` and calls
 *   [mirrorPending] once per sync run AFTER the outbox push. The upload never blocks a
 *   `master_items` row push the way the expense-attachment upload does (§8): a new
 *   photo's row syncs immediately with its local `image_path`; the Drive id follows.
 * - "Pending" is derivable state, not a queue table: a live `master_items` row whose
 *   `drive_image_id` is null and whose photo bytes exist on THIS device (the row's
 *   local `image_path`, or the legacy `{itemId}.webp` original). Only the device that
 *   took a photo can upload it.
 * - SILENT best-effort: not linked / offline / a per-item failure just leaves the row
 *   pending for the next sync run — no prompt, no error surface. An UNLINKED user's
 *   photo stays local-only (and renders locally); the mirror picks it up on the first
 *   sync after the account links.
 * - On success the implementation best-effort shares the file anyone-with-link
 *   (ADR-059 posture), stamps `drive_image_id` in Room AND enqueues the row upsert, so
 *   the id reaches the server (and the backup manifest, which already reads it).
 */
fun interface ItemPhotoDriveMirror {
    /**
     * Uploads every pending item photo to Drive. Returns how many rows gained a
     * `drive_image_id` (each also enqueued an outbox upsert the caller should drain).
     * Must never throw for expected conditions (not linked, offline, missing files).
     */
    suspend fun mirrorPending(): Int
}
