package com.itsluminous.samaroh.core.data.sync

/**
 * Mirrors inventory item photos to Google Drive as a DURABLE COPY (ADR-055, §9.1 layout
 * `Samaroh/{Business}/images/inventory/…`). Supabase Storage remains the SERVING source
 * (web parity, ADR-023) — Drive only backs the bytes up, so this mirror must never block
 * a `master_items` row push the way the expense-attachment upload does (§8).
 *
 * Contract (additive seam, same shape as [AttachmentUploader]):
 *
 * - Bound by `core:google`; `core:sync` consumes it as `Optional` and calls
 *   [mirrorPending] once per sync run AFTER the outbox push — by then a new photo's
 *   Storage upload has succeeded (the engine's ADR-023 mirror runs inside the push).
 * - "Pending" is derivable state, not a queue table: a live `master_items` row whose
 *   `drive_image_id` is null, whose `image_path` is already a Storage object path, and
 *   whose device-local photo file (`{itemId}.webp`) still exists. Rows synced FROM the
 *   web have no local file and are skipped — only the device that took the photo mirrors
 *   it.
 * - SILENT best-effort: not linked / offline / a per-item failure just leaves the row
 *   pending for the next sync run — no prompt, no error surface (the durable copy is
 *   opportunistic by design, like the ADR-028/053 Drive cascades).
 * - On success the implementation stamps `drive_image_id` in Room AND enqueues the row
 *   upsert, so the id reaches the server (and the backup manifest, which already reads
 *   `drive_image_id`).
 */
fun interface ItemPhotoDriveMirror {
    /**
     * Uploads every pending item photo to Drive. Returns how many rows gained a
     * `drive_image_id` (each also enqueued an outbox upsert the caller should drain).
     * Must never throw for expected conditions (not linked, offline, missing files).
     */
    suspend fun mirrorPending(): Int
}
