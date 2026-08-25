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

## ADR-007 — Additive sync bookkeeping in `core:database` (2026-08-25, W1-E)

**Status:** accepted.

The sync engine needs local-only bookkeeping beyond the Wave-0 `outbox` table. Added as
ADDITIVE changes (no existing column/signature touched; database still version 1 —
pre-release, no installed base, exported schema regenerated):

- New tables `sync_cursors` (per-business × per-table incremental pull cursor,
  `updated_at > last_pulled_at`; business-agnostic tables use the `*` scope) and
  `sync_conflicts` (persisted LWW conflict log: entity, title, overridden fields,
  resolution `rebased`/`dropped`, acknowledged flag driving the in-app banner). Neither
  table syncs.
- New DAOs `SyncCursorDao`, `SyncConflictDao`; new `SamarohDatabase`/`DatabaseModule`
  accessors.
- Additive `OutboxDao` methods: `pendingForEntity` (LWW lookup), `rewritePayload`
  (rebase), `erroredEntries` (Settings → Sync status per-item errors).
- Additive `ExpenseAttachmentDao.byId` so pulled attachment rows preserve the Room-only
  `local_cache_path`.

Pull-cursor edge case: the incremental filter is strict (`updated_at > cursor`), so two
rows sharing the exact same `updated_at` across a 200-row page boundary could skip one
row until its next update; accepted for v1 (server bumps `updated_at` per write).

## ADR-008 — Additive sync contracts in `core:data` (2026-08-25, W1-E)

**Status:** accepted.

Two additive interfaces join the Wave-0 sync contracts in `core.data.sync`:

- `SyncStatus` (+ `SyncItemError`, `SyncConflictEntry`, `ConflictResolution`): pending
  count, per-item errors, conflict log, last sync time, banner state, `syncNow()`,
  `acknowledgeConflict()`. Implemented by `core:sync` (`RoomSyncStatus`), consumed by the
  Menu tab (Settings → Sync status) and the app-bar cloud icon (§4.4/§4.5).
- `AttachmentUploader`: the §8 attachment queue contract — the sync engine uploads an
  `expense_attachments` file to Drive BEFORE pushing its metadata row. Declared as an
  OPTIONAL Hilt binding (`@BindsOptionalOf` in `core:sync`); `core:google` (W1-F)
  provides the real Drive implementation. While unbound/unlinked, attachment ops stay
  queued with the machine-readable per-item error code
  `attachment-pending-storage-link`.

LWW semantics implemented by W1-E (§8 "rebased or dropped", never silent): a pulled row
older than the newest pending op ⇒ local wins (remote row skipped; push will carry the
local edit up). A pulled row newer ⇒ pending DELETE (or a remote tombstone) drops the
local op; a pending UPSERT is REBASED — the local field values that differ from the
remote row (audit columns excluded) are re-applied on top of it, the consolidated op is
requeued with a fresh `updated_at`, and Room gets the merged row. Both paths persist a
`sync_conflicts` entry, fire a localized notification and set the banner state. A pending
edit whose fields all match the newer remote row is simply cleared (no conflict).

Versions-catalog additions (additive): `supabase-postgrest` (supabase-kt 3.0.3) +
`ktor-client-okhttp` (Ktor 3.0.3) for the Postgrest wire, `androidx-lifecycle-process` +
`androidx-startup-runtime` for the app-foreground sync trigger (registered from the
`core:sync` manifest — no `:app` change).
## ADR-009 — W1-D additive edits: auth dependencies + invite-lookup DAO query (2026-08-25)

**Status:** accepted.

W1-D (`feature:onboarding` + `core:auth`) makes two additive-only edits outside its
exclusively-owned modules:

1. **Versions catalog** (`gradle/libs.versions.toml`): adds `io.github.jan-tennert.supabase`
   `auth-kt`/`postgrest-kt` 3.0.3, `io.ktor:ktor-client-okhttp` 3.0.3 (the supabase-kt 3.0.x
   line pairs with Ktor 3.0.x), `androidx.credentials` 1.3.0 (+ `credentials-play-services-auth`)
   and `com.google.android.libraries.identity.googleid:googleid` 1.1.1 — the §1.1 stack for
   Supabase auth/Postgrest and Sign-in with Google via Credential Manager. No existing
   entries changed.
2. **`core:database` `BusinessMemberDao`**: adds one query method
   `membershipsForEmail(email): Flow<List<BusinessMemberEntity>>` (case-insensitive
   `invited_email` match, tombstones excluded). Purely additive — no entity, schema or
   existing-signature change (DB version stays 1). Needed for pending-invite auto-detection
   in onboarding (§4.0 step 4) and reusable by the Menu Members screen.

Also note: `core:auth` gains its own `BuildConfig` fields (`SUPABASE_URL`,
`SUPABASE_ANON_KEY`, `GOOGLE_WEB_CLIENT_ID`) read from `local.properties` with safe empty
defaults, mirroring `:app` — feature/core modules must not depend on `:app`, and empty
values degrade gracefully (auth reports "not configured"; the Google button shows a
localized disabled state).
## ADR-010 — Additive booking-domain contract extensions for the reminder engine (2026-08-25)

**Status:** accepted. **Author:** W1-A (`feature:booking`).

The Wave 0 frozen contract had no persistence path for `payment_reminders` and no
one-shot queries suitable for the daily reminder worker (§4.1). Purely **additive**
extensions (no existing signature changed, no schema/version change):

- `core:database` `BookingDao`: `bookingsEndedBefore(businessId, date)` (payment-reminder
  candidate set) and `bookingsStartingOn(businessId, date)` (upcoming-event reminders).
- `core:database` `BookingPaymentDao`: `paymentsForBookings(bookingIds)` — reactive input
  for the month summary card ("Received ₹X · Pending ₹Y").
- `core:database` `PaymentReminderDao`: `remindersForBooking(bookingId)`,
  `duePendingRemindersOnce(businessId, onOrBefore)`, `byId(id)`.
- `core:data` `BookingRepository` (+ `RoomBookingRepository`, `Mappers`): mirrors of the
  above plus `saveReminder`/`reminder` — reminder writes go through Room + outbox exactly
  like every other synced table (`payment_reminders` payloads carry Long paise, ADR-002).

Rationale: reminders are synced rows (§2), so the feature layer must not talk to DAOs
directly; the repository stays the single Room+outbox write path.
## ADR-011 — Additive expenses ledger-support contracts (2026-08-25, W1-B)

**Status:** accepted.

`feature:expenses` needs queries and seams that the frozen Wave-0 contracts do not carry.
All changes are strictly ADDITIVE — no existing signature, entity, or schema changed (Room
stays at version 1; only new `@Query` methods, no entity/column changes):

- `core:database` `ExpenseDao` (new methods): `byId(id)`; `totalPaiseFlow(businessId,
  direction)` (live "You gave"/"You got" header totals); `lastEntryPerParty(businessId)`
  returning the new `PartyLastEntryRow` projection ("last entry" relative time on the
  party list).
- `core:database` `ExpenseAttachmentDao` (new method): `attachmentsForParty(partyId)` —
  one query for all ledger-row thumbnails of a party.
- `core:data` new `ExpensesLedgerRepository` (+ `RoomExpensesLedgerRepository`,
  `ExpenseTotals`, `AttachmentWithLocalState`): totals, last-entry map, single-row
  lookups, and attachment-metadata persistence (metadata row + outbox upsert/tombstone;
  the file bytes never sync — Google Drive is authoritative per §2/§4.2). The Room-only
  `local_cache_path` is exposed via `AttachmentWithLocalState`, NOT by widening the frozen
  `ExpenseAttachment` model.
- `core:data` new `AttachmentUploadQueue` contract (`enqueue(localPath, expenseId)`), same
  Wave-0 seam pattern as `OutboxWriter`/`InvoiceGenerator`: features enqueue Drive uploads
  without depending on `core:google`. Pending state = the metadata row with
  `drive_file_id IS NULL` (drives the visible pending badge). A `LocalOnlyAttachmentUploadQueue`
  placeholder is bound in the new `ExpensesLedgerModule` (own file; `DataModule` untouched);
  `core:google` (W1-F) supersedes that binding with the real Drive uploader at integration.
## ADR-012 — FIFO inventory calculator and overview queries (W1-C) (2026-08-25)

**Status:** accepted.

Additive, inventory-domain-only extensions to the Wave 0 contracts (spec §4.3):

- `core:database` `InventoryTransactionDao` gains two read-only queries (no entity or
  schema change, Room version stays 1):
  - `currentInventory(businessId)` — per-item aggregate rows (`CurrentInventoryRow`):
    stock = Σ(add) − Σ(remove); value = Σ(remaining_quantity × unit_price) over open
    `add` lots, rounded to whole paise. Mirrors the canonical Postgres helper the web
    app uses, so both clients compute identical numbers.
  - `transactionCountForItem(masterItemId)` — counts ALL transaction rows including
    tombstoned ones. Tombstoned rows still exist server-side, so they keep blocking
    master-item deletion (the can-delete rule).
- `core:data` gains a new file with `CurrentInventoryLine`, the read-side interface
  `InventoryOverviewRepository` (current-inventory flow + `canDeleteMasterItem`), and
  `FifoInventoryRepository` — a decorator over `RoomInventoryRepository` that implements
  the FIFO lot logic anticipated by the Wave 0 `InventoryRepository.recordTransaction`
  contract note:
  - `add` → `remaining_quantity` is forced to the added quantity (a new open lot);
  - `remove` → rejected when quantity exceeds open stock; otherwise consumes open `add`
    lots oldest-first, decrementing each lot's `remaining_quantity` (each touched lot is
    re-enqueued to the outbox so lot state syncs), and the remove row is stored with the
    FIFO **weighted-average unit cost** in paise and `remaining_quantity = 0`.
  - Quantities are rounded to 3 decimals (numeric(10,3) parity); per-lot costs round to
    whole paise (ADR-002).
- `DataModule` now binds `InventoryRepository` to `FifoInventoryRepository` (and adds a
  binding for `InventoryOverviewRepository`). The frozen `InventoryRepository` interface
  itself is unchanged; `RoomRepositories.kt` is untouched.
## ADR-013 — Additive `SyncStatusProvider` contract in `core:data` (2026-08-25, W1-F)

**Status:** superseded at Wave-1 integration — W1-E's richer `SyncStatus` API (ADR-008
addendum below) won the seam. The `SyncStatusProvider` contract and the `feature:menu`
fallback (`OutboxSyncStatusProvider`) were DELETED; the menu Sync-status screen consumes
`core:data`'s `SyncStatus` interface implemented by `core:sync` (`RoomSyncStatus`).

The §4.4 "Sync status" screen needs pending count / per-item errors / last-sync time, but
Wave 0 defined no read-side sync contract. W1-F adds **additive** types to
`core:data/sync/SyncStatus.kt` (`SyncStatus`, `SyncItemError`, `SyncStatusProvider`) next
to the frozen `SyncScheduler`/`OutboxWriter`. Nothing existing changed.

- `feature:menu` ships a FALLBACK implementation (`OutboxSyncStatusProvider`) that reads
  the outbox directly: pending count and errors are real, `lastSyncAt` stays null.
- **INTEGRATOR:** the real provider is a W1-E (`core:sync`) deliverable. When it lands,
  remove the `@Binds` for `OutboxSyncStatusProvider` in `feature:menu`'s `MenuModule` —
  it is the only wiring point.

## ADR-014 — `DriveUploader` contract lives in `core:google` (2026-08-25, W1-F)

**Status:** accepted.

No `AttachmentUploadQueue` contract existed in `core:data` when W1-F implemented the
Drive REST v3 uploader, so the additive `DriveUploader` interface (+ `DriveTarget`,
`DriveFileRef`, `DriveLayout` §9.1 path mapping) is defined in `core:google` itself.

- Root folder id is cached in `google_accounts.drive_root_folder_id` per §9.1.
- **INTEGRATOR:** if W1-B lands an `AttachmentUploadQueue` contract in `core:data`, its
  implementation should delegate to `DriveUploader` (queue semantics on top of this
  transport) rather than duplicating the folder/upload logic.

## ADR-015 — Calendar sync targets the primary calendar (2026-08-25, W1-F)

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

## ADR-016 — Single shared settings DataStore in `core:data` (2026-08-25, integration)

**Status:** accepted.

`feature:booking` (reminder prefs delegate) and `feature:menu`
(`SettingsPreferencesDataSource`) both opened the preferences file `"settings"` with their
own DataStore instances. Two DataStore instances on one file throw
`IllegalStateException` at runtime, so the instance itself is now a contract:
`core:data`'s `SettingsDataStoreModule` provides the ONE Hilt singleton
(`@SettingsDataStore DataStore<Preferences>`) and every consumer injects it. The key
namespace already agreed across features (`theme_mode`, `dynamic_color`,
`booking_reminder_*`); the `:app` shell adds `onboarding_complete` for first-launch
routing.

## ADR-017 — `ActiveBusinessProvider` / `CurrentUserProvider` session contract (2026-08-25, integration)

**Status:** accepted.

Every Wave-1 feature carried its own "which business / which user" assumption (booking's
owner-mode actor provider, expenses' fixture-id defaults, inventory's and menu's
first-live-business lookups). Integration unifies them behind a small ADDITIVE contract
in `core:data/session/SessionContracts.kt`:

- `ActiveBusinessProvider.activeBusiness: Flow<Business?>` — v1 single-business: the
  first live local business.
- `CurrentUserProvider.currentUserId: Flow<String?>` — null while signed out.

Implementations live in `core:auth` (`SessionActiveBusinessProvider`,
`SessionCurrentUserProvider` on top of `SessionHolder`). Signed-out/offline default is
unchanged: owner-mode on the first local business, so the app stays fully usable before
sign-in. Consumers: booking's `SessionBookingActorProvider`, expenses' `ExpensesSession`
(also the `expenses.edit` gate), all three inventory view models, and menu's
`CurrentBusinessProvider` façade.

## ADR-018 — One Drive pipeline for expense attachments (2026-08-25, integration)

**Status:** accepted.

W1-B's `AttachmentUploadQueue` (`core:data.attachments`, ADR-011) and W1-E's optional
`AttachmentUploader` (`core:data.sync`, ADR-008) genuinely overlap: both mean "upload this
expense attachment to Drive and stamp `drive_file_id`". They are unified in `core:google`:

- `DriveAttachmentUploader` implements `AttachmentUploader` using `DriveUploader`
  (§9.1 `invoices/expenses/{party}/` layout); the sync engine invokes it while draining
  the outbox (upload-before-row-push, §8) and patches `drive_file_id` into the payload
  and the local row.
- `DriveBackedAttachmentUploadQueue` implements `AttachmentUploadQueue`: callers persist
  the metadata row + outbox upsert first (contract KDoc), so `enqueue` resolves the row
  via `local_cache_path` scoped to the expense and nudges `SyncScheduler` — cheap,
  idempotent, offline-safe.
- `LocalOnlyAttachmentUploadQueue` is deprecated and unbound; both bindings live in
  `GoogleModule`.

## ADR-019 — Additive read-side reports contract (2026-08-25, W2-A)

**Status:** accepted.

The §4.4 report set needs two cross-entity range queries the Wave-0 contracts do not
carry. All changes are strictly ADDITIVE — no existing signature, entity or schema
changed (Room stays at version 1; only new `@Query` methods):

- `core:database` `BookingPaymentDao.paymentsBetween(businessId, from, to)` — live
  payments by `paid_on` in a window, independent of the paid booking's own dates.
  Cash-basis income input for the profit report (a late payment for last season's event
  counts in the month it was received).
- `core:database` `ExpenseDao.expensesBetween(businessId, from, to)` — live entries of
  every party in a window (the per-party `entriesForParty` would need one live query per
  party for the expense-summary/profit reports).
- `core:data` new `ReportsRepository` interface + `RoomReportsRepository`
  (`repository/ReportsSupport.kt`) exposing the two queries as domain-model flows, bound
  in the new `ReportsSupportModule` — same pattern as ADR-011's
  `ExpensesLedgerRepository`: a NEW interface in its own file, so no frozen contract and
  no other module's test fakes change; `DataModule` stays untouched.

Everything else `feature:reports` consumes comes from existing contracts:
`BookingRepository.bookingsBetween`/`paymentsForBookings` (revenue, dues aging,
occupancy, breakdowns, collection efficiency), `ExpensesRepository.partiesWithBalance`
(party names), and `InventoryOverviewRepository.currentInventory` (FIFO valuation).

## ADR-020 — UX-feedback wave: reminder kinds, display icon, manual invoice numbers, form-field prefs (2026-08-25)

**Status:** accepted.

Additive contract changes for the owner's UX-feedback round:

1. **`core:model` `ReminderKind`** (`payment` | `follow_up`) and
   `PaymentReminder.kind: ReminderKind = PAYMENT` marked `@Transient`: tentative-booking
   follow-up reminders reuse the `payment_reminders` row shape, discriminated by a
   LOCAL-ONLY kind. The canonical Postgres table has no such column, so the kind never
   enters outbox/sync payloads; sync pulls preserve the local value via
   `PaymentReminderDao.byId` in `LocalApplier` (exact `local_cache_path` precedent from
   ADR-007/ADR-008). Trade-off: on another device the same reminder row appears as a
   plain payment reminder — acceptable for v1 (follow-ups are dismissed engine-side when
   the booking is no longer tentative).
2. **`core:database` v2** (first real migration — pre-release installs exist now):
   `ALTER TABLE payment_reminders ADD COLUMN kind TEXT NOT NULL DEFAULT 'payment'`
   (`SamarohDatabase.MIGRATION_1_2`, wired in `DatabaseModule`; exported schema 2.json).
3. **`core:model` `Booking.displayIcon`** (+ `TENTATIVE_ICON` 👤): presentation helper —
   tentative bookings render 👤 everywhere (calendar cells/pills, agenda, card title,
   calendar-sync event title) regardless of event type; confirming reverts to the stored
   `event_icon`, which is untouched.
4. **Manual invoice numbers**: `BookingDao.countInvoiceNumberUses` +
   `BookingRepository.invoiceNumberExists(businessId, invoiceNumber, excludingBookingId)`
   — the booking form's optional manual invoice-number field validates per-business
   uniqueness before persisting. The number stays editable ONLY while
   `bookings.invoice_number` is null; once set (manually or by the allocator) it is
   frozen, and `RoomInvoiceNumberAllocator` keeps returning it without consuming a
   counter value (existing idempotence covers manual numbers).
5. **Settings DataStore keys** (device-local, shared `"settings"` file — contract like
   the `booking_reminder_*` keys): booking-form field visibility booleans
   `booking_form_show_security_deposit` (default **false**),
   `booking_form_show_source` (default true), `booking_form_show_times` (default true).
   Written by `feature:menu` (Settings → Booking form fields), read by
   `feature:booking`'s form.
