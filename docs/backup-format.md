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
└── tables/
    ├── businesses.json
    ├── business_members.json
    ├── business_settings.json
    ├── bookings.json
    ├── date_blocks.json
    ├── booking_payments.json
    ├── payment_reminders.json
    ├── parties.json
    ├── expenses.json
    ├── expense_attachments.json
    ├── master_items.json
    └── inventory_transactions.json
```

Excluded by design: `google_accounts` (per-user row, no business data; ADR-003 keeps
tokens server-side anyway) and `outbox` (device-local queue, §8).

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
  ]
}
```

- `attachments` lists every Drive-hosted binary referenced by exported rows:
  `expense_attachments.drive_file_id` and `master_items.drive_image_id`
  (`mime_type` is null for inventory images). Binaries are NOT inside the ZIP — they
  already live in Drive under the same business folder (§9.1); the manifest records the
  ids a restore needs to re-fetch or re-link them.

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
| `scopes` and other list columns | embedded JSON array string |
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
   business's Drive folder).
