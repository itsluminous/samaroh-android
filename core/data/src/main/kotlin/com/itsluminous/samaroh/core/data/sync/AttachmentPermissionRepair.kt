package com.itsluminous.samaroh.core.data.sync

/**
 * Ensures Drive-hosted media carry the anyone-with-link reader permission so business
 * MEMBERS — whose Google tokens cannot read files another account's app created under
 * `drive.file` — can still view them via the public-link download path. Covers expense
 * bills (ADR-059: Android resolver fallback and the web ledger's Drive links) AND
 * inventory item photos (ADR-063: Drive is their image store now, same posture).
 *
 * Contract (additive seam, same shape as [ItemPhotoDriveMirror]):
 *
 * - Bound by `core:google`; `core:sync` consumes it as `Optional` and calls
 *   [repairPending] once per sync run after the outbox push.
 * - "Pending" is derivable state: a live `expense_attachments` row with a
 *   `drive_file_id` (or a live `master_items` row with a `drive_image_id`) whose
 *   DEVICE-ONLY `drive_permission_ensured` flag is unset. New uploads ensure the
 *   permission inline; this pass retroactively covers media uploaded before
 *   ADR-059/063 and any upload whose inline permission call failed.
 * - Throttled to a fixed budget of rows per run; the backlog drains across syncs.
 * - SILENT best-effort: not linked / offline / a per-item failure leaves rows pending
 *   for the next run. A definitive not-my-file answer (the Drive file belongs to
 *   another member's account) marks the LOCAL flag so this device stops retrying —
 *   the flag never syncs, so the uploader's own device still repairs the file.
 */
fun interface AttachmentPermissionRepair {
    /**
     * Ensures the link-reader permission on pending rows. Returns how many rows this
     * run settled (permission ensured, or confirmed un-repairable from this device).
     * Must never throw for expected conditions (not linked, offline, missing files).
     */
    suspend fun repairPending(): Int
}
