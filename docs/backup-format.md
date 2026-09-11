# Backup Format

Implemented by `core:google` (`BackupExporter` → `BackupArchive` → `BackupEngine`,
scheduled by `BackupWorker`/`BackupScheduler`). Spec §4.4: disaster recovery if the
primary backend is lost — another database can be seeded from one archive plus the
Drive-hosted attachments it references.

## Where backups go

```
Drive: Samaroh/{Business Name}/backups/backup-YYYY-MM-DD-HHmm.zip
```

- File name: `backup-` + `yyyy-MM-dd-HHmm` in the **device's local time zone** + `.zip`.
- Uploaded with the `drive.file` scope; the `Samaroh` root folder id is cached in
  `google_accounts.drive_root_folder_id` (§9.1).
- Frequencies: `daily` / `weekly` (default) / `monthly` (30 days) / `manual`, from
  `business_settings.backup_frequency`, run as WorkManager periodic jobs (network
  constrained); "Back up now" enqueues a one-shot job. Owner-only: the Settings section is
  hidden for employees and the underlying tables are RLS-guarded.
- On success `business_settings.last_backup_at` is updated through the repository
  (Room + outbox), so the "Last backup" line syncs like any other setting.

## ZIP layout

```
backup-2026-08-25-0900.zip
├── manifest.json
├── logo.webp          ← business logo bytes (optional; the ONLY binary allowed)
└── tables/
    ├── businesses.json
    ├── business_members.json
    ├── business_settings.json
    ├── event_types.json
    ├── bookings.json
    ├── date_blocks.json
    ├── booking_payments.json
    ├── payment_reminders.json
    ├── parties.json
    ├── expenses.json
    ├── expense_attachments.json
    ├── master_items.json
    ├── inventory_transactions.json
    ├── notes.json
    ├── note_tags.json
    └── note_tag_links.json
```

**Content contract (references-only).** The archive contains table data + *references*
to images — never the image bytes themselves:

- **Bills / expense attachments and inventory item photos are NOT packed.** They are
  already mirrored to Drive (`expense_attachments.drive_file_id`,
  `master_items.drive_image_id`, ADR-055/§9.1), so re-packing them daily would bloat
  every archive with bytes Drive already holds. The manifest records the Drive ids a
  restore needs; device-only columns (`local_cache_path`, `image_path`) appear in the
  row JSON as plain strings but are meaningless off-device (they never sync — the
  server tables do not carry them; drop them when seeding Postgres).
- **The business logo is the sole exception.** `businesses.logo_path` points at a file
  that is *not* Drive-mirrored (a device file on Android; the `logos` Storage bucket
  copy is what web reads for invoices) — the one asset a total-loss disaster would otherwise destroy. When
  the business has a logo and its file is readable on the exporting device, its bytes are
  embedded as `logo.<ext>` (normally `logo.webp`, ≤320px WebP per ADR-050). A safety cap
  of 1 MiB (`BackupArchive.MAX_LOGO_BYTES`) skips oversized legacy files so the archive
  can never balloon with image bytes.

**Excluded by design** (audited against the full canonical schema; the exporter's
schema-guard test fails the build if a future synced table is neither exported nor on
one of these lists):

- `google_accounts` — per-user, not per-business. It carries no business data; tokens
  were never stored client-side (ADR-003), and its non-secret columns (`email`,
  `scopes`, `drive_root_folder_id`, `calendar_id`) are Google-account identifiers that
  a post-disaster restore **re-derives when the user re-links Google**. After a
  backend loss the auth user ids themselves are recreated, so an exported row would
  only dangle — deliberately NOT exported, not even partially.
- `outbox`, `sync_cursors`, `sync_conflicts` — device-local sync machinery (§8),
  meaningless off-device.

The notes domain (`notes`, `note_tags`, `note_tag_links`) and `event_types` carry **no
file assets** — a checklist is a JSON text column, not a file — so they add nothing to
the attachment manifest.

## `manifest.json`

```json
{
  "format_version": 1,
  "created_at": "2026-08-25T03:30:00Z",
  "business_id": "…uuid…",
  "business_name": "Sharma Hall",
  "money_unit": "paise",
  "tables": [
    { "name": "bookings", "row_count": 12, "file": "tables/bookings.json" }
  ],
  "attachments": [
    {
      "table": "expense_attachments",
      "row_id": "…uuid…",
      "drive_file_id": "1AbC…",
      "file_name": "bill.pdf",
      "mime_type": "application/pdf"
    }
  ],
  "logo": { "file": "logo.webp", "source_path": "/data/…/files/logos/business-logo-….webp" }
}
```

- `attachments` lists every Drive-hosted binary referenced by exported rows:
  `expense_attachments.drive_file_id` and `master_items.drive_image_id`
  (`mime_type` is null for inventory images). Binaries are NOT inside the ZIP — they
  already live in Drive under the same business folder (§9.1); the manifest records the
  ids a restore needs to re-fetch or re-link them.
- `logo` (optional, additive — still format v1) references the embedded logo entry:
  `file` is the ZIP entry name, `source_path` the `businesses.logo_path` value at export
  time. Absent when the business has no logo, the file was unreadable on the exporting
  device, or it exceeded the 1 MiB cap.

## `tables/<table>.json`

A JSON **array of row objects**. Rows are raw snapshots of the Room tables, whose columns
mechanically mirror the canonical Postgres schema — keys are the **schema column names**
(`customer_name`, `total_amount`, …). Value conventions (from the Room storage layer):

| Kind | Representation |
|---|---|
| Money (`total_amount`, `amountPaise`, …) | integer **paise** (ADR-002). Divide by 100 for decimal rupees when seeding Postgres. |
| Instants (`created_at`, `updated_at`, `deleted_at`, `transaction_date`) | integer epoch **milliseconds** UTC |
| Dates / times (`start_date`, `paid_on`, `start_time`, …) | ISO-8601 `TEXT` (`2026-09-10`, `18:30`) |
| Enums (`status`, `method`, `direction`, …) | Postgres wire strings (`confirmed`, `cash`, `paid`, …) |
| `permissions` (business_members) | embedded JSON document string (permissions-schema.json) |
| `scopes`, `checklist` and other list/document columns | embedded JSON array/document string |
| Booleans | 0 / 1 integers |
| NULL | JSON `null` |

**Tombstones are included** (`deleted_at != null`) — a restore must preserve them so sync
convergence and audit history survive the disaster.

## Restore procedure (manual, v1)

1. Unzip; read `manifest.json`, check `format_version == 1`.
2. Create the business row from `tables/businesses.json`, then load every other table in
   the manifest order (no FK constraints assumed — same as sync, ADR-004).
3. Convert values per the table above (paise → `numeric` rupees, epoch ms →
   `timestamptz`, wire strings → enums).
4. Re-link attachments by `drive_file_id` from the manifest (files still live in the
   business's Drive folder). Bills and item photos are **recovered from Drive by id**,
   not from the ZIP — the archive never carries their bytes.
5. Restore the business logo **from the ZIP** (`manifest.logo.file`): upload the embedded
   bytes to the `logos` Storage bucket (or the new device's files dir) and point
   `businesses.logo_path` at the new location. The logo is the only asset whose bytes the
   archive carries, because it is not Drive-mirrored.
