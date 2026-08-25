# Architecture Decision Records

Every change to a frozen contract (`core:model`, `core:database`, repository interfaces in
`core:data`, the canonical Supabase schema, the string-catalog key namespace) MUST be
recorded here before it merges.

---

## ADR-001 — Wave 0 contract freeze (2026-08-25)

**Status:** accepted.

The following are FROZEN as of Wave 0 completion. Wave 1+ agents change them only through
the integrator plus a new ADR here:

- `core:model`: enums (`BookingStatus`, `PaymentMethod`, `ReminderStatus`, `TxnType`,
  `ExpenseDirection`, `BookingSource`, `MemberStatus`), permission types matching
  `shared/permissions/permissions-schema.json`, and the domain model data classes.
- `core:database`: Room entities for every canonical schema table + the local-only
  `outbox` table, DAO signatures, `SamarohDatabase` (version 1, `exportSchema = true`,
  committed schema JSON under `core/database/schemas/`).
- `core:data`: repository interfaces (`BookingRepository`, `ExpensesRepository`,
  `InventoryRepository`, `BusinessRepository`, `MemberRepository`) and the sync contracts
  (`OutboxWriter`, `SyncScheduler`, `OutboxOperation`) — defined in Wave 0 so features
  never block on the sync engine implementation.
- String-catalog key namespaces (owned per feature agent; keys live ONLY in
  `samaroh-shared`, generated at build time by `generateStrings`).

New string keys within a feature's own namespace may be added freely in `samaroh-shared`.

## ADR-002 — Money as Long minor units (paise) (2026-08-25)

**Status:** accepted.

Postgres stores money as `numeric(12,2)` rupees. On Android, all money is represented as
`Long` **paise** (fields suffixed `Paise`), in both `core:model` and Room columns:

- SQLite has no decimal type; `INTEGER` paise makes `SUM()` aggregation exact
  (payments totals, party net balances) with no floating-point drift.
- `AmountFormatter` is the single paise → display-string conversion (Indian grouping).
- The sync engine (W1-E) owns the wire conversion paise ⇄ decimal rupees. Outbox
  `payload_json` written in Wave 0 carries paise values under the Postgres column names;
  W1-E must divide/multiply by 100 at the Postgrest boundary.

Inventory quantities (`numeric(10,3)`) remain `Double` — 3 decimal places is well within
double precision, and quantities are not summed for accounting purposes.

## ADR-003 — `google_accounts` synced without the token column (2026-08-25)

**Status:** accepted.

The canonical `google_accounts` table includes `refresh_token_cipher`. The Room mirror
(`GoogleAccountLinkEntity`) deliberately omits it: refresh tokens must never leave
server-side storage (spec §6 security). The client only needs email/scopes/folder/calendar
ids to render Settings and drive uploads. Sync pulls for this table must select the
non-secret columns only.

## ADR-004 — No SQLite foreign-key constraints (2026-08-25)

**Status:** accepted.

Room entities declare indices but no `@ForeignKey` constraints. The sync engine applies
pulled rows per table in arbitrary order (a child row can arrive before its parent within
a pull window), so local FK enforcement would break sync. Referential integrity is owned
by Postgres; repositories enforce app-level invariants (e.g. positive amounts).

## ADR-005 — Outbox uses an autoincrement PK (2026-08-25)

**Status:** accepted.

The `outbox` table is local-only (never synced), so the client-UUID rule does not apply.
An `INTEGER PRIMARY KEY AUTOINCREMENT` id gives collision-free, monotonic FIFO ordering:
`ORDER BY id` IS the queue order, immune to same-millisecond `created_at` ties.

## ADR-006 — Invoice contract in `core:data` (2026-08-25)

**Status:** accepted.

`InvoiceGenerator` and `InvoiceNumberAllocator` (package
`core.data.invoice`) join the Wave 0 frozen contracts so `feature:booking` (W1-A) can wire
the invoice/share actions without depending on the renderer, which `core:invoice` (W1-E)
implements (spec §4.1, §11 critical-path note — same pattern as `OutboxWriter`).

- `generateInvoicePdf(bookingId): Result<String>` — absolute path of the rendered PDF.
- `buildInvoiceText(bookingId): String` — localized plain-text receipt (share-as-text).
- Invoice numbers `{prefix}-{YYYY}-{counter:04d}` are assigned once per booking and are
  immutable afterwards (`bookings.invoice_number`); allocation is idempotent.
- All amounts are Long paise (ADR-002), rendered via `AmountFormatter` only.

## ADR-007 — Additive `SyncStatusProvider` contract in `core:data` (2026-08-25, W1-F)

**Status:** accepted.

The §4.4 "Sync status" screen needs pending count / per-item errors / last-sync time, but
Wave 0 defined no read-side sync contract. W1-F adds **additive** types to
`core:data/sync/SyncStatus.kt` (`SyncStatus`, `SyncItemError`, `SyncStatusProvider`) next
to the frozen `SyncScheduler`/`OutboxWriter`. Nothing existing changed.

- `feature:menu` ships a FALLBACK implementation (`OutboxSyncStatusProvider`) that reads
  the outbox directly: pending count and errors are real, `lastSyncAt` stays null.
- **INTEGRATOR:** the real provider is a W1-E (`core:sync`) deliverable. When it lands,
  remove the `@Binds` for `OutboxSyncStatusProvider` in `feature:menu`'s `MenuModule` —
  it is the only wiring point.

## ADR-008 — `DriveUploader` contract lives in `core:google` (2026-08-25, W1-F)

**Status:** accepted.

No `AttachmentUploadQueue` contract existed in `core:data` when W1-F implemented the
Drive REST v3 uploader, so the additive `DriveUploader` interface (+ `DriveTarget`,
`DriveFileRef`, `DriveLayout` §9.1 path mapping) is defined in `core:google` itself.

- Root folder id is cached in `google_accounts.drive_root_folder_id` per §9.1.
- **INTEGRATOR:** if W1-B lands an `AttachmentUploadQueue` contract in `core:data`, its
  implementation should delegate to `DriveUploader` (queue semantics on top of this
  transport) rather than duplicating the folder/upload logic.

## ADR-009 — Calendar sync targets the primary calendar (2026-08-25, W1-F)

**Status:** accepted.

The task-mandated incremental scopes are `drive.file` + `calendar.events` (least
privilege). `calendar.events` can create/update/delete events but **cannot create
calendars** (that would need the full `calendar` scope). The one-way push (§4.1)
therefore writes to the linked account's **primary** calendar; `google_accounts.
calendar_id` stores `"primary"` (schema slot kept so a dedicated calendar can be adopted
later without migration). Per-device change detection (bookingId → eventId +
content fingerprint) lives in a local DataStore (`gcal_sync_state`); the synced
`bookings.gcal_event_id` column still records event ids for other devices, written
through `BookingRepository.saveBooking` (Room + outbox).

## Wave 1-F additive-change log (2026-08-25)

Non-ADR changes W1-F made outside its owned modules, all additive:

- `gradle/libs.versions.toml`: added `androidx-credentials` (+ play-services-auth
  variant) 1.3.0, `googleid` 1.1.1, `play-services-auth` 21.3.0,
  `kotlinx-coroutines-play-services`, `androidx-hilt-work`, `androidx-work-testing`.
  No existing versions changed.
- `core:google/build.gradle.kts` now mirrors :app's `local.properties` →
  `BuildConfig.GOOGLE_WEB_CLIENT_ID` pattern (a library module cannot read :app's
  BuildConfig). Empty value = localized "not configured" degradation
  (docs/google-setup.md).
- `google_accounts` link writes also enqueue an outbox upsert of the non-secret columns
  (consistent with offline-first writes; ADR-003 respected — no token column exists
  client-side).
- Device settings DataStore file **"settings"** is provided by `feature:menu`'s
  `MenuModule` (`@SettingsDataStore DataStore<Preferences>`). Contract keys (consumed by
  `feature:booking`'s reminder engine): `booking_reminder_lead_days: Set<String>`,
  `booking_reminder_style: String (notification|fullscreen)`,
  `booking_reminder_sound_uri: String`. **INTEGRATOR:** if W1-A also needs the store,
  inject this binding rather than opening the file twice (DataStore forbids two
  instances on one file).
- Theme preferences (`theme_mode`, `dynamic_color`) are written by `feature:menu`;
  **INTEGRATOR:** the app shell should read `SettingsPreferencesDataSource.settings` and
  feed `SamarohTheme(darkTheme, dynamicColor)` — W1-F must not touch `:app`.
- `feature:menu`'s Members screen embeds a clearly-marked `PermissionMatrixEditorSlot`
  placeholder; swap for `core:auth`'s `PermissionMatrixEditor` (W1-D) at merge.
- `menuGraph()` gained an optional `onOpenReports: () -> Unit = {}` parameter — the :app
  call site compiles unchanged; wire it to the reports graph when W2-A lands.
- Workers in `core:google` are plain `CoroutineWorker`s resolved via Hilt entry points,
  so no `Configuration.Provider` change in `:app` is required.
