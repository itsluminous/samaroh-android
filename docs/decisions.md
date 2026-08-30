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

## ADR-021 — Additive `recordTransactionForValue` on `InventoryOverviewRepository` (2026-08-27, inventory parity)

**Status:** accepted.

The transaction success feedback (snackbar) must surface the FIFO cost of a remove —
a value `FifoInventoryRepository.removeFifo` already computes but discarded. The frozen
`InventoryRepository.recordTransaction` contract returns `Unit`, so instead of changing
it, the W1-C-owned `InventoryOverviewRepository` (ADR-007) gains one additive method:

```kotlin
suspend fun recordTransactionForValue(txn: InventoryTransaction): Long
```

It records exactly like `recordTransaction` (which now delegates to it) and returns the
transaction's total value in **Long paise** (ADR-002): quantity × unit price for adds,
the consumed-lot FIFO cost for removes. No schema, sync, or frozen-interface change;
existing callers of `recordTransaction` are unaffected.

## ADR-022 — Human-readable Sync status entries (2026-08-27, sync-list UX)

**Status:** accepted.

Owner feedback: the pending-sync list showed raw entity/technical data. The Sync status
screen must render each outbox row as a localized human line — operation verb + entity
noun + human identifier, e.g. "Add booking — Sharma", "Update inventory item — Spoon",
"Delete booking — 28 Jan 2027" — with the technical detail (table · op · id) available
on tap/expand. Error rows get the same headline and keep their sanitized message.

Additive changes to frozen contracts:

1. `core:database` — `OutboxDao.pendingEntries(): Flow<List<OutboxEntity>>` (full queue,
   push order) and a new read-only `SyncDisplayDao` with scalar per-id lookups
   (booking customer name/start date, party/item/business/member names, attachment file
   name, expense→party id, payment→booking id/amount, reminder→booking id, date-block
   start date, txn→item id). The lookups deliberately skip the `deleted_at` filter: a
   queued delete's row is already tombstoned locally, but its name is still the best
   display identifier. No schema change — DAO only, DB version stays 2.
2. `core:data` — `SyncStatus` gains `pendingItems: Flow<List<SyncPendingItem>>` (new
   data class: outboxId, entityType, entityId, operation, payloadJson, queuedAt), and
   `SyncItemError` gains `payloadJson: String = ""` (defaulted — existing constructions
   unaffected). `RoomSyncStatus` is the only implementation.
3. Mapping lives in `feature:menu` (`SyncEntryDisplayResolver`): payload JSON is parsed
   generically (`ignoreUnknownKeys`), identifier resolution is payload-first → local Room
   row → 8-char short id. Add vs Update for upserts is derived from the payload
   timestamps: every repository creates rows with `created_at == updated_at` (single
   shared `now`) and bumps only `updated_at` on edit, so equality ⇒ Add. Payloads without
   `created_at` (business_settings, google_accounts) render as Update. Verb/noun words
   are catalog keys composed via `settings.sync.op_phrase` ("{verb} {noun}" en,
   "{noun} {verb}" hi — Hindi uses gender-neutral infinitives: "बुकिंग जोड़ना").

## ADR-023 — Item photos: dual-form `image_path` + Storage mirroring on sync (2026-08-27, web/Android parity)

**Problem.** Imported and web-added `master_items` carry `image_path` = Supabase Storage
object paths in the private `inventory-images` bucket (`{business_id}/{item_id}/{file}`);
web renders them via signed URLs. Android treated EVERY `image_path` as a local file
(`AsyncImage(model = File(path))`), so remote-only photos never rendered. Conversely,
Android-added photos stored only a device-local absolute path — pushed as-is, they were
meaningless to the web (the Wave-1 "photos stored locally, mirroring deferred" gap).

**Decision.**
1. `image_path` officially carries TWO forms, classified by prefix
   (`core:data/image/ItemImageSource.kt`): absolute local paths (`/…`, `file:`,
   `content:`) = a photo on this device not mirrored yet; anything else = a Storage
   object path.
2. **Display** (new `ItemImageResolver` interface in `core:data`, bound in `core:auth` to
   `StorageItemImageResolver`): local paths load as files; storage paths load via the
   bucket's stable AUTHENTICATED object URL (`…/object/authenticated/…`) with the current
   access token as a header. Authenticated URLs — not signed URLs — because a signed
   URL's token changes on every creation and would defeat Coil's cache keys (and creating
   one is itself a network call, so offline could not even build the request). Coil
   requests pin `memoryCacheKey`/`diskCacheKey` to the raw object path, and the app-wide
   `ImageLoader` sets `respectCacheHeaders(false)`, so once fetched a photo renders
   offline indefinitely, even signed out.
3. **Mirroring** (`ItemImageMirror` in `core:sync`, mirroring the attachment queue
   contract): before a `master_items` upsert pushes, a local `image_path` is uploaded to
   `inventory-images` at `{business_id}/{item_id}/{millis}.webp` and the payload is
   patched to the object path in the outbox AND Room. Timestamped names mean a replaced
   photo gets a NEW path — no stale caches on any platform. Transient upload failures
   keep the op queued (pending state); a vanished local file pushes `image_path = null`
   rather than blocking the entity forever. A device-local path can no longer reach the
   server.

**Contract note.** `core:data` gains the additive `image/ItemImageSource.kt` (constant,
prefix classifier, `ItemImageResolver` interface). No frozen repository interface, Room
schema or wire format changed. Known small leak: replacing/removing a photo orphans the
previous ~15 KB Storage object; acceptable for now.

## ADR-024 — Keyset sync pull, reminder cleanup pass + post-sync hooks (2026-08-27, the "so many reminders" bug)

**Problem.** The pull cursor was timestamp-only (`updated_at > cursor`, one 200-row page
per step, stop when the newest timestamp equals the cursor). The 2026-08-26 booking
import stamped ALL 805 bookings and 632 payments with ONE transaction `now()` — so every
device pulled exactly 200 rows per table and could NEVER see the rest (strict `>`
excludes ties forever). The 259 in-SQL settlement payments were among the lost rows, so
the reminder engine computed `due > 0` for dozens of long-settled bookings and created
pending reminders with past `remind_on` — the "so many reminders" card. Worse, each
device created its OWN reminder row and pushed it (2 duplicates per booking, 124 rows on
the server). Two more gaps: the daily reminder worker was only scheduled on Booking-tab
entry (a fresh install that never opens the tab gets NO notifications), and reminders
pulled/settled via sync waited for the next daily 09:00 pass to be acted on.

**Decision.**
1. **Keyset pagination** (`core:sync` + `core:database`): the pull cursor is now the pair
   `(last_pulled_at, last_pulled_id)` (Room v3, additive `sync_cursors.last_pulled_id`).
   The remote query fetches rows strictly after that position in `(cursorColumn, id)`
   ascending order (`or=(ts.gt.X,and(ts.eq.X,id.gt.Y))` — verified against live
   PostgREST). A null id (legacy/pre-migration cursor, or fresh EPOCH) pulls `>=` the
   timestamp, so upgraded installs automatically re-fetch the ties they lost —
   idempotent LWW applies make that safe.
2. **Reminder cleanup pass** (`feature:booking`): `PaymentReminderPlanner.plan` keeps
   exactly ONE pending reminder per booking (earliest wins; duplicates from concurrent
   devices are dismissed), and the old "orphan" pass became `staleDismissals`, judged per
   booking: a due pending reminder is dismissed when its booking is missing, cancelled,
   soft-deleted, or has `due <= 0` — which also covers "total unknown (0)": such bookings
   never remind. It runs over ALL due pending reminders (not just locally-ended
   bookings), so reminders synced from another device clean up too.
3. **Post-sync hooks** (`core:data` additive `PostSyncHook` contract, Hilt `@IntoSet`):
   after a sync run whose pull applied rows, the engine invokes contributed hooks
   (failures swallowed). `feature:booking` contributes `ReminderPostSyncHook`: re-ensure
   the daily worker + run a full engine pass, so a fresh install's FIRST pull immediately
   registers notifications/alarms and dismisses stale reminders.
4. **Startup registration** (`feature:booking`): `BookingReminderStartupInitializer`
   (androidx.startup + ProcessLifecycleOwner, same pattern as `SyncStartupInitializer`)
   re-ensures the daily worker on every process ON_START. Notifications now also set
   `setOnlyAlertOnce(true)` so post-sync re-posting updates silently.

**Contract note.** Frozen-contract touches, all additive: `sync_cursors` column +
migration 2→3, `SyncCursorDao.cursor()` now returns the entity, `PostSyncHook` in
`core:data`. Server rows already polluted by the bug are dismissed by
`Planning/cleanup-stale-reminders.sql` (owner-run); devices also self-heal via 1+2.

## ADR-025 — Interactive square photo cropper in `core:designsystem` (2026-08-27, upload-crop parity with web)

**Problem.** The web app lets the user choose the crop when uploading a photo; Android
silently center-square-cropped (inventory item photos, onboarding logo) or stored the raw
picked file with no processing at all (settings business-profile logo). The owner
requires user-controlled cropping on upload for item photos and the business logo —
expense attachments are exempt (they are documents and must stay readable/uncropped).

**Decision.**
1. **Hand-rolled Compose cropper** in `core:designsystem`
   (`component/cropper/SquareImageCropper.kt`): a full-screen dialog with a fixed square
   viewport; the image is pinch-zoomed (about the gesture centroid) and panned via
   `pointerInput { detectTransformGestures }` and drawn on a `Canvas`
   (translate → scale → drawImage), with a border + rule-of-thirds grid. No third-party
   crop library: the geometry is ~100 lines of pure Kotlin (`SquareCropMath`), fully
   unit-tested, and avoids an unmaintained/heavyweight dependency.
2. **Pure geometry** (`SquareCropMath`): zoom ∈ [1, 8] over a "fill" base scale, pan
   clamped so the viewport never leaves the image, and `cropRegion` maps the viewport
   back to source pixels (always in-bounds, side ≥ 1) — so the confirmed crop is exactly
   what was on screen.
3. **Source loading** (`loadCropSourceBitmap`): bounded two-pass decode (≤~1600px longest
   side) plus EXIF upright rotation via the new `androidx.exifinterface:exifinterface`
   dependency (AndroidX, Apache-2.0, pinned 1.4.1) — an interactive cropper cannot show a
   sideways image, unlike the old blind center crop.
4. **Wiring** (picker/camera → cropper → existing WebP ≤320px pipelines):
   `feature:inventory` `ItemImageStore.compressItemImage` now takes the cropped `Bitmap`
   (was `Uri`); onboarding's camera/gallery both feed the cropper before
   `LogoProcessor.process(Bitmap)` (the `Uri` overload was removed); the settings
   business-profile logo switches to the system photo picker and now ALSO compresses to
   WebP ≤320px (it previously copied raw bytes — a fidelity fix folded into this change).
5. **Strings**: new `common.cropper.*` keys live in the shared fragment
   `strings/fragments/designsystem.{en,hi}.json` (designsystem is integrator-owned, so
   the `common.*` namespace is correct).

**Consequences.** Any future square-crop need (member avatars, event photos) reuses the
same dialog. Expense attachments intentionally keep their existing uncropped
`AttachmentCompressor` path. The stored-file formats and the sync/Storage mirroring
contracts (ADR-023) are unchanged — the cropper only decides WHICH square goes into the
existing ≤320px WebP files.

## ADR-026 — Inventory purchases count as spend in the money reports (2026-08-27, web parity)

**Status:** accepted.

**Context.** Stock bought through the Inventory tab (`add` transactions) is real money
out, but it never creates an expense ledger row, so the Expense summary and Profit
reports under-reported spend. samaroh-web shipped the fix (8dbdc17) with the shared
string key `reports.expense.inventory_purchases_label` (shared 40970d1); Android must
match.

**Decision.** Inventory `add` transactions are valued at quantity × unit price and
counted as spend in the money reports, bucketed by the month of `transaction_date` — NO
expense ledger rows are created:

1. `core:database` (additive `@Query`, Room stays at version 1):
   `InventoryTransactionDao.addTransactionsBetween(businessId, fromInclusive,
   toExclusive)` — live `add` rows of every item in a half-open Instant window
   (mirrors web's `gte`/`lt next-day` bounds).
2. `core:data` (additive method on the ADR-019 `ReportsRepository`, no other contract
   or fake changes): `inventoryPurchasesBetween(businessId, fromInclusive, toExclusive)`
   returning domain `InventoryTransaction`s; `RoomReportsRepository` gains the DAO.
3. `feature:reports` calculators (pure, unit-tested): shared
   `inventoryPurchasesByMonth(purchases, range, zone)` rounds quantity × unit-price to
   whole paise PER TRANSACTION, then sums per month. New
   `ExpenseSummaryCalculator.byMonth` returns per-month ledger ('paid' entries only,
   web-parity with `expenseSummaryByMonth`) + inventory + total; `ProfitCalculator`
   adds the month's purchases to its (net paid − received) expenses.
4. Presentation: the Expense summary chart/table become monthly (Month | Expenses |
   Inventory purchases | Total, stacked bars) and the spend-by-party breakdown moves to
   a secondary on-screen table excluded from CSV/PDF — exactly the web layout. Profit
   is unchanged visually; its expense column simply includes purchases.

**Timezone.** `transaction_date` is an Instant; months are bucketed in the device zone
(`Clock.zone` — the same zone the inventory UI uses to display transaction dates), and
the query window is [range.start 00:00, range.end+1day 00:00) in that zone. Web buckets
by the UTC date of the ISO timestamp; the two can differ around local midnight — accepted,
since each client is consistent with its own displayed dates.

**Consequences.** Expense summary totals and Profit nets now include stock purchases,
matching web. The ledger column intentionally counts 'paid' entries only (web parity)
while the per-party breakdown keeps netting paid − received, same as before.

## ADR-027 — Personal parties: `parties.business_related`, report exclusion + Personal-expenses report, total rows, machine-readable CSV (2026-08-27)

**Status:** accepted.

**Context.** Owners record spend on people unrelated to the business (family, personal
loans) in the same party ledger; those entries polluted the Expense summary and Profit
reports. Shared migration `004_party_business_flag.sql` adds
`parties.business_related boolean not null default true` server-side.

**Decision.**
1. **Contract changes (frozen, hence this ADR):**
   - `core:model` `Party` gains `@SerialName("business_related") businessRelated:
     Boolean = true` — defaulted so rows pulled from a pre-migration server decode as
     business-related, and appended last so positional constructions stay valid.
   - `core:database` v4 (`MIGRATION_3_4`): `ALTER TABLE parties ADD COLUMN
     business_related INTEGER NOT NULL DEFAULT 1`; exported schema 4.json; no DAO
     signature change (entities carry the flag through existing queries).
   - Sync wire: `business_related` rides the existing generic pipeline — outbox party
     payloads are the serialized model (`encodeDefaults = true`), so every upsert now
     carries the flag; `WireConverter` passes booleans through untouched; pulls select
     `*` and `LocalApplier` decodes via the defaulted model field.
2. **⚠️ ORDERING REQUIREMENT — server migration FIRST.** Because `encodeDefaults = true`
   puts `business_related` in EVERY party upsert payload, PostgREST rejects party pushes
   (unknown column, PGRST204) until the owner applies shared migration 004. That failure
   is non-fatal by design: the §8 push loop records a per-item error, holds only that
   party's ops (other entities keep syncing) and retries next run — self-healing once the
   migration lands. Pulls never break (select `*`). The field is deliberately NOT gated
   client-side: a gate would silently drop the personal flag on the server and let the
   two sides diverge.
3. **UX:** "Add person" is now "Add party" (shared key value change). The add-party
   screen and an edit-party dialog on the ledger get a yes/no pill "Associated with
   {business}?" (default YES); personal parties show a subtle localized "Personal" tag
   on party rows and the ledger header.
4. **Reports:** personal parties' entries are EXCLUDED from the Expense-summary and
   Profit calculators (exclusion sets computed from the live party list) and listed
   exclusively by the NEW Personal-expenses report (`ReportType.PERSONAL_EXPENSES`):
   net spend per personal party per month, date-range filter, CSV/PDF export. The two
   sides are exact complements — no entry is dropped from both.
5. **Total rows:** every tabular money report (revenue, dues aging, event types,
   sources, expense summary + its party sub-table, profit, inventory valuation, personal
   expenses) renders a final localized TOTAL row on screen, in the PDF (bold, ruled
   off) and in the CSV. Profit totals income / expense / net. Pure sums live in
   `ReportTotals` (unit-tested). Occupancy and collection are not money tables and keep
   no total row.
6. **Machine-readable CSV:** `ReportTable` gains `totalRow` plus CSV-only `csvRows` /
   `csvTotalRow`; the CSV export writes plain unformatted amounts (decimal rupees, two
   decimals, no ₹, no grouping — `CsvValues.rupees`) and ISO dates/months so spreadsheet
   apps parse them as numbers/dates. On-screen and PDF keep `AmountFormatter`/localized
   dates unchanged.

## ADR-028 — Party edit/delete parity: cascade tombstone + `ChipRow` scrollable filter pills (2026-08-27)

**Status:** accepted.

**Context.** The ledger's edit affordance only toggled ADR-027's business flag, and there
was no way to delete a party at all. Separately, the owner's Profit-report screenshot
showed the date-preset chip row WRAPPING on narrow screens — the last "Custom" pill was
squashed to one character per line. Both fixes touch frozen contracts, hence this ADR.

**Decision.**
1. **Party edit parity.** The ledger's edit-party dialog now carries the full add-party
   form: name (trimmed, deduped case/whitespace-insensitively against the business's
   other live parties via `FuzzyNameMatcher.normalize`, excluding the party itself),
   optional phone, and the ADR-027 business/personal pill. Saves go through the existing
   `ExpensesRepository.saveParty` (UPSERT outbox row). Gate: `expenses.edit` OR
   `expenses.manage_parties` (owners always pass) — `ExpensesSession.canManageParties`.
2. **Party delete = cascade tombstone.** ADDITIVE contract methods (frozen files):
   - `core:database` `ExpenseDao.liveForParty(partyId)` and
     `ExpenseAttachmentDao.liveForParty(partyId)` — one-shot live rows of a party.
   - `core:data` `ExpensesLedgerRepository.deletePartyCascade(partyId): List<String>` —
     tombstones children first (attachments → expenses → party), enqueues one outbox
     DELETE per row (id + `deleted_at` payload, same shape the sync engine already
     pushes), and returns the `local_cache_path` of every tombstoned attachment; the
     ViewModel deletes those on-device files. Attachments with a `drive_file_id` only
     get their metadata tombstoned — Drive-side purge is out of scope pre-OAuth.
   The server needs no new endpoint: the cascade is N ordinary tombstones in FIFO outbox
   order (children before parents mirrors the FK order). Gate: `expenses.delete`
   (`ExpensesSession.canDeleteParties`); the confirmation dialog warns that all entries
   and attached bills die too (`expenses.party.delete_confirm_*` keys), and the UI
   navigates back to the party list after `PartyLedgerEvent.PartyDeleted`.
3. **`ChipRow` in `core:designsystem`.** One reusable horizontally scrollable, never
   wrapping quick-filter row (`Row` + `horizontalScroll`, 8dp spacing, optional
   `contentPadding` INSIDE the scroll area so edge padding scrolls with content, and a
   `chip_row` test tag). Swapped in everywhere chips form a quick-filter row: report
   date presets, theme + backup-frequency chips (Settings), reminder lead-day chips,
   booking status/follow-up/source chips, payment-method chips (record-payment sheet),
   permission preset chips and the business-related yes/no pill. NOT converted:
   `MasterlistScreen` duplicate suggestions (already a `LazyRow`), 2-option
   `SegmentedButton` toggles (fixed-width by design), and lone `AssistChip` badges.

## ADR-029 — Events view windowing + `isSyncing` + inventory master-item gate (2026-08-27)

**Status:** accepted.

**Context.** Three same-day features touch frozen contracts additively: (1) the Booking
calendar gains an "Events view" — the month grid swaps for a FULL agenda list of every
booking, grouped by date and anchored at today, which must not eagerly load a
multi-year booking history; (2) the app-bar cloud icon must visibly animate while a
sync run executes, but `SyncStatus` exposed no "running right now" signal (only queue
counts); (3) the item-detail screen gains the Masterlist's edit/delete affordances,
which need the same permission gate.

**Decision.**
1. **Windowed events agenda.** ADDITIVE contract methods (frozen files):
   - `core:database` `BookingDao.minStartDate/maxStartDate(businessId)` — `MIN`/`MAX`
     over live rows (ISO-8601 TEXT compares chronologically).
   - `core:data` `BookingRepository.bookingDateBounds(businessId): ClosedRange<LocalDate>?`
     — earliest..latest live start date, null when the business has no bookings.
   The agenda reuses the existing `bookingsBetween` overlap query over a window that
   starts today-centred (−2/+4 months, `EventsAgenda.initialWindow`) and grows in
   6-month steps when the LazyColumn nears an edge, CLAMPED to the date bounds — so
   scrolling reaches every booking without ever querying empty decades or loading 800
   rows up front. Keys (`d:<date>` / `b:<id>`) keep the viewport stable when older
   items are prepended. The toggle lives in the calendar's existing overflow menu
   (label flips Month view ⇄ Events view, `booking.calendar.*_view` keys) and persists
   per device via the shared settings DataStore (`booking_calendar_events_view` —
   device UI state, never synced). Tapping a row opens the SAME booking-card sheet;
   the `detail` flow now falls back to `bookingRepository.booking(id)` for rows
   outside the shown month.
2. **`SyncStatus.isSyncing`** (ADDITIVE on the frozen `core:data` sync contract): true
   while a sync run (push+pull) executes. Implementation: a `core:sync` `SyncRunState`
   singleton the `SyncWorker` raises around `SyncEngine.runSync()` (try/finally so a
   crash never leaves it stuck; WorkManager serializes the unique work so a boolean
   suffices). The app-bar `SyncCloudIcon` spins the `CloudSync` glyph (1.2s linear
   rotation) while `isSyncing`; with reduced motion on, a static badge dot appears
   instead (§6 motion policy).
3. **`InventorySession.canManageMasterItems`** (`feature:inventory`, ExpensesSession
   pattern): owners always pass, else `inventory.manage_master_items` OR
   `inventory.edit`; signed-out/offline stays owner-mode (true). Gates the Masterlist
   FAB + row edit/delete AND the new item-detail overflow (Edit item / Delete item),
   which reuses the extracted `MasterItemEditorDialog`/`MasterItemDeleteDialogs` —
   identical dup validation, unit dropdown, photo crop and the
   delete-blocked-if-transactions rule; a confirmed delete pops back from the detail
   screen.

**Consequences.** Web/sync are unaffected (no schema/wire change). The events window
resets to today's anchor on process death (per-device UI state only). `isSyncing` is
process-local by design — a run on another device shows up via pulled data, not the
spinner.

## ADR-030 — Booking colour: `bookings.color` palette key end-to-end (2026-08-27)

**Status:** accepted.

**Context.** Owners want to colour-code bookings (Google-Calendar-style) so the month
grid tells events apart at a glance. This touches the frozen contracts (model, Room,
sync wire) plus the shared schema (migration 005) and palette
(`shared/booking-colors.json`, 16 curated swatches with an AA-checked `on_hex` each).

**Decision.**
1. **Contract (additive).** `Booking.color: String?` (`@SerialName("color")`, default
   null) — a palette KEY (e.g. `peacock`), never a raw hex, so palette tweaks restyle
   history and unknown keys degrade to the default look. Room v4→v5
   (`MIGRATION_4_5`: `ALTER TABLE bookings ADD COLUMN color TEXT`, exported schema
   5.json); both entity⇄model mappers carry it. NULL = the default themed
   (tertiary-container) look.
2. **Sync wire.** No wire special-casing: `encodeDefaults = true` puts `color` in every
   booking upsert payload, `WireConverter` passes non-money/non-enum keys through
   untouched, pulls select `*` and decode via the defaulted model field.
   **⚠️ ORDERING — server migration 005 FIRST** (same PGRST204 self-healing behaviour
   as ADR-027's party flag): until the owner applies shared migration 005, PostgREST
   rejects booking pushes (unknown column); the §8 push loop records a per-item error,
   holds only those ops and retries next run. Pulls never break. The field is
   deliberately NOT gated client-side — a gate would silently drop colours server-side.
   Verified empirically on 2026-08-27: the live project still returns
   `42703 column bookings.color does not exist` — 005 NOT yet applied.
3. **Palette loading.** `shared/booking-colors.json` is copied into `feature:booking`'s
   generated assets at build time (`copyBookingColors`, same single-source-of-truth
   pattern as event-types.json). `BookingColorsProvider : BookingColorCatalog` parses
   `{key, hex, on_hex, label_key}`; label keys resolve to generated string resources;
   entries with unrecognized keys are dropped (forward compatibility).
4. **Form.** Add + edit get a "Colour" row (`booking.form.color`): a Default swatch
   (null — slash + outline, the standard "no colour" vocabulary) followed by the 16
   swatches, 4 per row. Each swatch is a ≥48dp `Role.RadioButton` target whose
   contentDescription is the localized colour name; the selected swatch carries a 3dp
   primary ring plus a check mark tinted with the palette's `on_hex`.
5. **Rendering.** Month cell FILL uses the booking's colour only when EXACTLY ONE live
   booking covers the date AND it is firm (confirmed/completed — tentative never
   colours; it keeps the amber outline + 👤 regardless). Multi-booking days keep the
   default tertiary-container fill (no winner ambiguity). The date number on a coloured
   cell uses the palette's `on_hex` (every pair ≥ 4.5:1 AA). Agenda rows (month agenda
   + events view) show a small leading colour dot (decorative — text carries the
   information); the booking card shows a 14dp dot announcing the localized colour name.
   `CalendarMonthMapper.Day.fillColorKey` keeps the choice pure and unit-tested.

**Consequences.** Web should mirror the single-booking-fill / multi-default rule for
consistent cross-platform reading. Old app versions ignore the column entirely (Room
copy untouched pre-migration; model decode drops unknown keys server-side is N/A since
pulls map by model fields). A booking coloured with a FUTURE palette key renders the
default look here instead of crashing.

## ADR-031 — Per-event-type default booking colours (fallback chain) (2026-08-27)

**Status:** accepted.

**Context.** With ADR-030 only explicitly coloured bookings stand out; most owners never
pick a colour, so the month grid stays monochrome. `shared/event-types.json` now maps
each built-in type to a `booking-colors.json` key (wedding → tomato, engagement →
flamingo, tilak → tangerine, room_booking → blueberry, birthday → banana, anniversary →
sage, custom → grape), with a documented cross-app resolution contract.

**Decision.**
1. **Fallback chain**, applied EVERYWHERE a booking's colour renders (month cell fill —
   single-firm-booking rule unchanged — agenda/events dots, booking-card dot):
   explicit `bookings.color` → the event type's default colour → the standard themed
   look. `BookingColorFallback` (feature:booking domain) is the single resolver; a key
   that doesn't resolve in the palette falls THROUGH to the next step (future palette
   keys degrade gracefully). `CalendarMonthMapper.map` takes the resolver as a pure
   `(Booking) -> String?` parameter so the mapper stays Android-free and unit-tested.
2. **Custom stays themed.** Free-text event types (and the literal `custom` key a
   blank custom label stores) get NO type default on Android — the shared file's
   `custom: grape` is parsed but deliberately ignored by
   `EventTypeCatalog.defaultColorKeyFor` — an uncoloured custom booking keeps the
   themed (tertiary-container/purple) look unless explicitly coloured.
3. **Stored data unchanged.** `bookings.color` NULL still means "follow the type";
   nothing is backfilled, no schema/wire change. Retinting a type in the shared file
   restyles every uncoloured booking of that type, past and future.
4. **Form.** While no explicit colour is chosen, the picker highlights the current
   type's default swatch with a SECONDARY ring (distinct from the primary
   selection ring + check) announcing "Default — follows event type"
   (`booking.color.follows_type`). Picking a swatch stores an explicit key; picking
   Default stores null. Changing the event type moves the highlight live.

**Consequences.** Wedding/engagement/tilak/… bookings are now tell-apart-at-a-glance by
default; the web app should apply the same chain (contract documented in the shared
file's `$comment`). Old app versions simply keep the themed look for uncoloured
bookings (they never read the type colour). Tentative bookings remain never coloured
on the grid (amber outline + 👤 rule unchanged).

## ADR-032 — DB-backed event-type presets + manage screen (2026-08-28)

**Status:** accepted.

**Context.** Event types were 7 hard-coded entries in `shared/event-types.json`
(localized per-locale via catalog keys). Users want their OWN types; shared migration
006 introduces the per-business `event_types` table (`label`/`icon` plain-text user
data, `color` a booking-colors key, `sort_order`, soft delete, partial unique index on
live labels) and seeds every EXISTING business with the 7 built-ins in English (see
shared docs/event-type-presets.md). This ADR wires the Android side end-to-end and
touches the frozen contracts throughout.

**Decision.**
1. **Contract (additive).** `core:model EventType` mirrors the table; Room v5→v6
   (`MIGRATION_5_6`: CREATE TABLE `event_types` + business/sort index, exported schema
   6.json; deliberately NOT seeded — see 4). `EventTypeDao` +
   `core:data EventTypeRepository` (`presets` flow in sort order, save/delete via
   Room+outbox, case-insensitive `labelInUse` duplicate check — stricter than the
   server's case-sensitive index, `seedDefaults`).
2. **Sync wire.** `event_types` joins `SyncTables.ALL` (business-scoped, no
   money/enum fields) + a `LocalApplier` case. **⚠️ ORDERING — server migration 006
   FIRST**, with the ADR-030 self-healing philosophy extended to PULLS: a missing
   table is a PostgREST *rejection* on the table's pull, and the engine previously let
   that abort the whole run — `pullTableGuarded` now drops ONLY that table's pull for
   the run (pushes already held per-item), so everything else keeps syncing until 006
   lands. Verified empirically on 2026-08-28 against the live project (see report).
3. **Seeding (client-side, creation only).** Businesses created AFTER 006 are seeded
   by the creating client: `seedDefaults` inserts the 7 template entries from
   `event-types.json` (copied into core:data assets at build time) — **in ENGLISH**
   (labels resolved from the catalog with an English-forced configuration context).
   Chosen over current-locale seeding for cross-client uniformity with the server
   migration's rows and a locale-independent "Custom" row (see 5). `seedDefaults` is a
   no-op when the business has ANY `event_types` row, live or tombstoned — existing
   businesses (server-seeded) are never reseeded, and a user who deletes every preset
   stays at zero. Callers: onboarding create-business + the e2e fixture.
4. **Room migration does NOT seed.** A pre-006 device upgrade gets the rows from the
   server pull (the migration seeded them there); seeding locally too would duplicate
   (different client ids, same labels → server unique-index rejects on push).
5. **Booking form.** The dropdown lists the business's LIVE presets (sort order) plus
   the always-available free-text Custom entry. A preset normalized-named `custom` (the
   seeded "Custom" row) is REPRESENTED by that free-text entry rather than listed —
   never two Custom rows. Selection is `EventTypeChoice` (Preset | Custom); a save
   records the preset's CURRENT `label` + `icon` into `bookings.event_type`/`event_icon`
   — **snapshot semantics**: renaming/deleting a preset never rewrites old bookings
   (unit-tested). Editing a legacy booking whose `event_type` is a built-in KEY
   normalizes to the matching preset (saving re-records the label — an upgrade, not a
   bulk rewrite). New-booking default: the "wedding"-normalized preset, else the first.
6. **Colour fallback (revises ADR-031).** The type-default step of the chain now
   resolves from the business's PRESET rows: `EventTypePresets.defaultColorKeyFor`
   matches `booking.event_type` against live preset labels NORMALIZED (trim, lowercase,
   spaces→underscores) so legacy keys (`room_booking`) match their seeded row
   (`Room Booking`) and follow the user's recolouring; no match → themed purple.
   ADR-031's "custom is never coloured" special case is REMOVED: the literal
   `custom`/"Custom" now matches the Custom preset row (seeded grape) like any other —
   presets are uniformly the single colour source; free-text labels stay themed unless
   the user creates a same-named preset. `BookingColorFallback` takes
   `presets: List<EventType>`; the static catalog's `defaultColorKey` is gone
   (`event-types.json`'s `color` is now only the seed template value).
   `EventTypeCatalog` (renamed entry type `BuiltInEventType`) remains solely to
   localize legacy key-recorded bookings.
7. **Cross-feature moves.** The palette catalog (`BookingColor*`) moved
   feature:booking → `core:data.color`, and the 16-swatch picker generalized into
   `core:designsystem ColorSwatchPicker` (+ `parseHexColor`), because feature:menu
   needs both and feature modules never depend on each other.
8. **Manage screen.** Menu → Settings → "Event types", row + screen gated on owner or
   `settings.manage_business` (mirrors the server's write RLS). List = icon + label +
   colour dot in sort order; tap to edit; add/edit dialog = label (duplicate-validated,
   case-insensitive) + emoji text field (same vocabulary as the booking form's custom
   emoji) + the shared swatch picker (Default = themed); delete is SOFT with a
   confirmation stating old bookings keep their recorded type; reorder via up/down
   arrows swapping adjacent `sort_order` values (each swap pushes both rows).

**Consequences.** The picker is finally user-shaped; renaming "Wedding" retints and
relabels only FUTURE bookings (recorded ones are historical facts). An offline pre-006
business that never syncs has no preset rows — the form still works via the Custom
entry, and the manage screen can build a set from scratch. Web must apply the same
normalized-label colour contract for consistent cross-platform reading.

## ADR-033 — Android App Links for samaroh-web URLs (2026-08-28)

**Decision.** `https://samaroh-web.vercel.app/…` URLs open in the app via an
`android:autoVerify` VIEW/DEFAULT/BROWSABLE intent-filter on `MainActivity`
(now `launchMode="singleTask"` so warm links arrive through `onNewIntent`
instead of stacking shell instances).

1. **Parser.** `app/applink/AppLink.parse(path)` (pure Kotlin, unit-tested) maps the
   web path to a sealed `AppLink`: the `/{locale}` prefix (`en|hi`) is stripped; then
   `/booking` → Booking tab (calendar), `/expenses[/{partyId}]` → Expenses tab
   (ledger when the id exists locally), `/inventory[/masterlist]` → Inventory tab
   (masterlist toggle), `/menu` → Menu, `/menu/settings…` → Settings,
   `/menu/reports` → Reports; unknown/root/malformed → Booking.
2. **Routing.** Reuses the reminder-notification deep-link plumbing: MainActivity holds
   a `pendingAppLink` Compose state (cold start `onCreate`, warm `onNewIntent`);
   `SamarohApp` navigates to the tab with the bottom-bar pattern and hands sub-targets
   to the feature graphs via ADDITIVE default parameters (`expensesGraph(partyIdToOpen)`,
   `inventoryGraph(openMasterlist)`, `menuGraph(openSettings)`) with consumed-callbacks —
   the same contract-preserving shape `bookingGraph(bookingIdToOpen)` established.
3. **Graceful unknown ids.** `ExpensesDeepLinkViewModel.partyExists()` gates the ledger
   navigation; a stale/foreign party id lands on the party list.
4. **Verification.** autoVerify needs the web deploy to serve
   `https://samaroh-web.vercel.app/.well-known/assetlinks.json` with this package +
   signing-cert fingerprints (debug AND release). After it is live, re-check on device:
   `adb shell pm verify-app-links --re-verify com.itsluminous.samaroh` then
   `adb shell pm get-app-links com.itsluminous.samaroh` (expect `verified`).

**Consequences.** Web links shared over WhatsApp etc. open natively for app users; the
locale segment never overrides the in-app language preference. New web sections need a
parser case (defaulting to Booking until added).

## ADR-034 — Non-translatable catalog entries; launch-first UPI donate (2026-08-28)

**Context.** On real Android 11+ devices the About screen's Donate-via-UPI row showed
the "no UPI app" toast despite many installed UPI apps. `UpiDonate` pre-checked
`intent.resolveActivity(packageManager)` and bailed when it returned `null` — and
package visibility filters PackageManager query results on real devices even with the
app's manifest `<queries><intent>` declaration for the `upi` scheme (OEM builds filter
aggressively; `MATCH_DEFAULT_ONLY` resolution is fragile with multiple handlers). The
pre-check was the bug: `startActivity` itself is exempt from package-visibility
restrictions, so the launch would have succeeded.

**Decision (donate launch).** No PackageManager pre-check and no `createChooser`
(a chooser always resolves, so it can never signal "no app"): fire the plain
`ACTION_VIEW upi://pay…` intent and treat `ActivityNotFoundException` as the single
authoritative no-UPI-app signal (localized toast). With several UPI apps installed the
system shows its own disambiguation sheet. The manifest `<queries>` block is removed —
nothing queries other packages anymore.

**Decision (catalog contract).** The shared string-catalog entry shape gains an
optional `"translatable": false` flag for data-like values (URIs, technical
identifiers) that must never be localized:

1. Such entries live ONLY in the canonical `en` catalog/fragment; key parity excludes
   them, and an entry for one in `hi` (or any locale) is a hard validation error — a
   silently-ignored translation would drift from the canonical value.
2. `gen-android.mjs` emits them once, in default `values/strings.xml`, with
   `translatable="false"` (plus `formatted="false"` when the value carries a literal
   `%`, e.g. percent-encoding); every locale falls back to it.
3. `gen-web.mjs` copies the `en` value into every locale's messages file.
4. Plurals cannot be non-translatable.

First entries: `menu.about.donate_upi_uri` (the full UPI deep link — payee VPA, payee
name, percent-encoded note; previously Kotlin constants) and
`menu.about.source_code_url`. `CatalogTestSupport`/`CatalogKeyParityTest` mirror the
contract; fixture-driven pipeline tests live in the shared repo
(`scripts/test-catalogs.mjs`).

**Consequences.** The donate flow works wherever a UPI app is installed regardless of
package-visibility behavior; URIs are single-sourced in the shared catalog for both
platforms and can never be "translated" into broken links.

## ADR-035 — Icon-free PDF invoices (2026-08-28)

**Context.** The PDF event block printed `{icon} {EventType}` (e.g. `💒 Wedding`).
Emoji glyphs render inconsistently across PDF fonts and viewers — often as tofu or
mismatched monochrome glyphs — so the owner asked for icon-free PDF output.

**Decision.** The PDF renderer prints only the localized event-type label; the event
icon emoji no longer appears anywhere in the PDF. The shared layout contract
(`shared/invoice/layout-spec.md` §3 + Localization) now states "no event icons/emoji in
PDF output" for BOTH renderers (Android + web). The icon is UNCHANGED everywhere else:
app UI (calendar cells, booking cards, formatted titles), Google Calendar event titles,
and the plain-text receipt (plain text renders emoji fine; it goes through a share
intent, not a PDF font). `PdfInvoiceRendererContentTest` pins the contract by recording
`drawText` calls (LEGACY-graphics shadow) and asserting no surrogate pairs in en + hi;
`InvoiceTextBuilderTest` pins that the text receipt still carries the icon.

**Consequences.** Regenerated invoices for existing bookings lose the emoji (cosmetic
only; the invoice number is unchanged). The web renderer must apply the same spec change.

## ADR-036 — Outbox enqueue triggers a debounced sync (2026-08-28)

**Context.** Sync ran only on: connectivity-gated WorkManager requests, the 15-minute
periodic job, app launch/foreground resume, sign-in, and explicit "Sync now" /
per-feature `requestImmediateSync()` calls (only `feature:booking` and a few other spots
wired those). An edit made mid-session in any other feature sat in the outbox — the
cloud badge showed pending items until the user backgrounded the app, tapped Sync now,
or the periodic job fired. Owner question: "why don't changes sync right away?"

**Decision.** Additive `SyncScheduler.requestSyncOnLocalChange()` (default: delegates to
`requestImmediateSync()`, so fakes/simple impls stay valid). `RoomOutboxWriter.enqueue`
calls it after EVERY outbox insert, so all features get push-within-seconds for free —
no per-ViewModel wiring. The WorkManager implementation is a trailing debounce: unique
one-shot work (`samaroh-sync-on-change`) with a 3 s initial delay and
`ExistingWorkPolicy.REPLACE` — a burst of edits collapses into ONE run ~3 s after the
last write; a write landing while a change-sync is RUNNING replaces (cancels +
reschedules) it, so nothing is silently dropped (the engine is cancellation-safe:
idempotent remote upserts, outbox rows removed only after a successful push). No
`setExpedited` — WorkManager forbids expedited work with an initial delay, and a plain
request runs promptly while the app is foregrounded (it is: the user just edited). The
CONNECTED constraint means offline edits simply stay queued, exactly as before.

**Loop safety.** A sync run can never re-trigger itself: the engine applies pulled rows
via DAO upserts (`LocalApplier`) and drains/rewrites the queue via `OutboxDao` directly —
neither path goes through `OutboxWriter`, the only place the trigger lives.

**Consequences.** Existing `requestImmediateSync()` call sites in ViewModels are now
redundant but harmless (KEEP on a separate unique chain) and are left in place. The
15-minute periodic job remains the safety net for pull-side freshness.

## ADR-037 — Explicit invite accept for existing accounts (2026-08-28)

**Context.** The §3 invite flow only auto-activated memberships via a trigger on
`auth.users` INSERT (new signups). For a user whose auth account PRE-DATED the invite,
nothing ever linked `user_id`, and the consolidated RLS baseline (002) let a user read
`business_members` only where `user_id = auth.uid()` and update rows only as the owner —
so the invitee could neither SEE nor ACCEPT their own invitation; the join screen listed
nothing and the accept button was a pure client-side navigation that never activated the
membership (every `has_perm` check then failed and the business stayed invisible).

**Decision.** Shared migration 004 (server): a `BEFORE INSERT` trigger on
`business_members` links `user_id` when the invited email already has an auth account
(status stays `invited` — acceptance is an explicit user action on the join screen,
spec §4.0 step 4), invited-self SELECT policies (membership row + business name), a
self-activation UPDATE policy plus a guard trigger pinning the only permitted non-owner
change to `invited → active` on the caller's own row (owner-set fields immutable), and a
backfill for pre-existing invited rows. Client (additive contract change):
`MembershipRefresher.activateInvite(memberId)` performs the server-side activation and
applies the result to Room; `OnboardingViewModel.acceptInvite` only proceeds to
LINK_GOOGLE on confirmed activation and surfaces `onboarding.join.accept_failed`
otherwise. Signup auto-activation (spec §3) is unchanged — `activateInvite` treats an
already-active-for-me row as success, so the two paths cannot race into an error.

**Consequences.** Joining a business requires connectivity (acceptance is meaningless
offline — RLS gates all business data anyway). Existing-account invitees get an explicit
accept step; brand-new signups continue to skip it via server auto-activation.

## ADR-038 — Permission-gated bottom nav + hidden write affordances (2026-08-28)

**What.** Two-layer UI enforcement of the §3 permission object (owner requirement:
members must not *see* affordances they cannot use — hide, never grey):

1. **Tab level.** `app`'s `NavPermissions.visibleTabRoutes(isOwner, permissions)` maps
   `booking.view`/`expenses.view`/`inventory.view` to bottom-nav tabs; the Menu tab is
   unconditional. `MainViewModel.visibleTabs` recomputes reactively from
   `PermissionGuard` (null until the first emission — the shell waits, avoiding a
   flash of tabs that then vanish; signed-out/no-business keeps the owner-mode
   default of all tabs). The start destination is the *first visible* tab (Booking
   unless hidden), and a `LaunchedEffect` redirects to the first visible tab whenever
   a sync recompute revokes the module the user is currently on.
2. **Affordance level (additive session gates).** `InventorySession.canRecordTransactions`
   (`inventory.create`) hides the stock screen's record-transaction FAB and the item
   detail's Add/Remove buttons. `ExpensesSession.canCreateEntries` (`expenses.create`)
   hides the ledger's You gave/You got buttons; `canDeleteEntries` (`expenses.delete`)
   splits the entry menu so Edit needs `expenses.edit` and Delete needs
   `expenses.delete` independently. Booking's pending-confirmations card is hidden
   without `booking.record_payment`, the tentative follow-up card without
   `booking.edit` (its Cancel action additionally needs `booking.delete`).

**Why.** RLS already blocks the writes server-side, but viewers were shown dead or
error-producing buttons, and members without a module's `view` permission still got
the tab (empty/erroring). Postgres RLS remains the authoritative layer; this is §3
layer 2 only.

## ADR-039 — Per-module `view_amounts` masking + booking audit-line fix (2026-08-28)

**What.** The §3 permission object gains one key per money-bearing module —
`booking.view_amounts`, `expenses.view_amounts`, `inventory.view_amounts`,
`reports.view_amounts` (schema shared@d2c30b8) — the ONE exception to the
absent-defaults-false rule: **absent = TRUE** (pre-existing permission objects keep
showing amounts; Viewer/Staff/Manager presets leave it true; owners toggle it off per
member via the matrix editor's new "View amounts" row, which appears automatically from
the JSON projection). `MemberPermissions` mirrors this with `viewAmounts: Boolean = true`
(`@SerialName("view_amounts")`); Room's converter and the sync payload round-trip it
losslessly either way.

**Enforcement is presentation-layer masking, designed once in `core:designsystem`:**
`AmountText` gains a `masked` mode rendering `AmountFormatter.MASKED` (₹••• — symbol-only,
no catalog key) with a localized "Amount hidden" accessibility label
(`auth.permissions.amount_hidden_a11y`) and no money-in/out tone (the color would leak the
sign). Sessions expose `canViewAmounts` per module (`ExpensesSession`/`InventorySession`
flows, booking's `BookingActor.permissions.viewAmounts`, reports' home/detail states):

- **Booking** — month summary card (Received/Pending), card total/deposit/paid/due,
  payment-history rows, pending-confirmation reminder texts and the WhatsApp reminder
  prefill all mask; invoice generation is *blocked* (button hidden) without
  `booking.view_amounts` — an invoice IS the amounts. `record_payment` without
  `view_amounts` is deliberately left orthogonal: granting that mix is the owner's call.
- **Expenses** — gave/got totals, party net balances, entry amounts and balance-after
  chips mask.
- **Inventory** — stock total values, item total value, transaction values, unit prices
  and the FIFO-cost snackbar mask; quantities stay visible.
- **Reports** — `reports.view_amounts = false` hides MONEY reports entirely from the
  reports home (revenue, dues aging, event types, sources, expense summary, profit,
  inventory valuation, personal expenses); occupancy and collection-days — counts and
  durations — stay. The report *detail* gate also denies money reports, covering
  revoke-while-open. Simplest coherent rule: a money report without its figures is
  meaningless, and per-cell masking of charts/CSV/PDF exports would be leaky.

**Honest caveat.** This is presentation-layer only: the amounts still sync to the
member's device (Room rows are unchanged; Supabase RLS row-level policies are unchanged —
RLS filters rows, not columns). A determined employee can read the local database. Real
secrecy requires revoking the module's `view` permission (no rows at all); `view_amounts`
is a workplace-courtesy screen, not a security boundary.

**Audit-line fix (bundled).** The booking card's "Added by {name} on {date}" showed the
CURRENT user's name — `BookingCalendarScreen` passed `actor.displayName` (the active
session's actor) as `creatorName`, so every viewer saw themselves as the creator. The
`detail` flow now resolves the creator from `bookings.created_by` → `MemberRepository
.memberForUser().displayName`, falling back to `Business.ownerName` when `created_by` is
the owner (owners may lack a member row locally) and to the localized
`booking.card.audit_added_unknown_member` ("a member") when unknown.

## ADR-040 — Sign-out wipes all session-scoped local data (2026-08-30)

**What.** The Menu identity row gains a sign-out icon (right side, `ExplainableIcon`,
Logout glyph) shown only while a session exists — offline/owner-mode ("Not signed in")
has no icon. Tapping it opens a confirmation dialog: when `SyncStatus.pendingCount > 0`
the body is the ICU plural `menu.sign_out.confirm_message_pending` ("N changes not yet
synced will be lost…"), otherwise the plain `menu.sign_out.confirm_message`. Confirming
runs, in order: (1) `SessionHolder.signOut()` — hardened in `SupabaseAuthManager` to
`clearSession()` locally when the server-side revoke fails offline; (2) the new
`core:data` **`SignOutCleaner.clearAll()`** (additive session contract); (3) a one-shot
`SignedOut` event the app shell answers by navigating to the onboarding **sign-in step**
(`ONBOARDING_SIGN_IN_ROUTE`, new optional `startAtSignIn` nav arg — the device already
has a chosen language) with `popUpTo(0)`, so back cannot return to signed-in UI.

**Local-data decision.** Sign-out clears the ENTIRE Room database
(`clearAllTables()`: business data, outbox, sync cursors, conflict log, Google link
rows), resets the `onboarding_complete` flag (a restart lands on onboarding, not on
empty tabs in owner mode), and clears every module-contributed `SessionScopedStore`
(Hilt `@IntoSet`, same pattern as ADR-024's `PostSyncHook`): `core:sync` wipes the
sync-meta DataStore (last-sync time), `core:google` wipes the per-device gcal push-state
DataStore. This is the simplest CORRECT behavior for a shared device: the next sign-in
— any account — re-pulls from the server from cursor zero, and the previous user's data
never leaks to the next one. The cost is deliberate: **unsynced outbox changes are
discarded**, which is exactly what the dialog's pending-count warning surfaces before
the user commits. Device-level preferences (theme, language, reminder settings, form
toggles) are kept — they carry no user data. Scheduled WorkManager sync runs are not
cancelled: a post-sign-out run finds an empty outbox and an anon client (RLS filters
every read to nothing) and no-ops.
