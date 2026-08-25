# Backup Format

> Placeholder — the backup engine is implemented by W1-F (`core:google`). This document
> must be completed alongside it so another database could be seeded from a backup.

## Contract (from the spec, §4.4)

- Backup = a ZIP of per-table JSON exports + an attachments manifest, uploaded to Drive at
  `Samaroh/{Business}/backups/backup-YYYY-MM-DD-HHmm.zip`.
- Owner-only: backup settings and "Back up now" are hidden from employees and RLS-guarded.
- Frequencies: daily / weekly / monthly / manual.
- Purpose: disaster recovery if the primary backend is lost.

## To document when implemented (W1-F)

- Exact ZIP layout and file naming.
- Per-table JSON schema (field names, money representation, tombstone handling).
- Attachments manifest format and how Drive file ids are recorded.
- Restore procedure.
