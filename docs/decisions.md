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

## ADR-041 — Marker event types (`event_types.kind`) (2026-09-02)

**Status:** accepted.

**Context.** Owners record auspicious days (Lagan, Tilak) as "bookings" purely to see
them highlighted on the calendar — they have no customer, no payments, and pollute the
event-type report. The shared schema adds `event_types.kind text not null default
'booking' check (kind in ('booking','marker'))` (shared@0baf15e: baseline 001, idempotent
`scripts/alter-event-type-kind.sql` for the live DB, seed template flags lagan/tilak as
markers). This ADR wires the Android side end-to-end; it touches the frozen contracts.

**Decision.**
1. **Contract (additive).** `core:model EventTypeKind` (BOOKING|MARKER, wire lowercase);
   `EventType.kind` defaulted to BOOKING so rows pulled from a server WITHOUT the column
   decode unchanged (same additive-column pattern as ADR-027/030). Unlike the other
   enums, `fromWire` is TOLERANT (unknown → BOOKING): a future kind value degrades to
   the ordinary treatment instead of failing. Room v6→v7 (`MIGRATION_6_7`: `ALTER TABLE
   event_types ADD COLUMN kind TEXT NOT NULL DEFAULT 'booking'`, exported schema 7.json);
   converter stores the wire string.
2. **Sync wire.** `event_types` gains `enumFields = setOf("kind")` — local payloads carry
   the serial name ("MARKER"), the wire the lowercase check-constraint value ("marker").
   **⚠️ ORDERING — server column FIRST** (ADR-030 philosophy): until the owner applies
   the shared ALTER script, PostgREST rejects event-type pushes (PGRST204 unknown
   column) — the §8 push loop holds ONLY those ops per-item and retries; pulls never
   break (defaulted decode). Deliberately NOT gated client-side.
3. **Kind resolution is normalized-label matching** — `core:model EventTypeKinds`
   (normalize + kindFor + isMarker), the exact contract `EventTypePresets` (ADR-032)
   established for colours, hoisted to core:model because feature:reports needs it too
   and feature modules never depend on each other (`EventTypePresets.normalize` now
   delegates). A booking matching NO live preset is always a real booking — free-text
   types and tombstoned presets are never silently demoted to markers.
4. **Seeding.** `EventTypeSeed`/`AssetEventTypeSeedTemplate` carry the template's `kind`
   (absent → booking); the label map gains the template's `lagan`/`muh_dikhayi` keys
   (previously skipped for lack of a catalog string — both keys exist now).
5. **Calendar precedence.** `CalendarMonthMapper.map` gains a pure
   `isMarker: (Booking) -> Boolean` parameter: on a date covered by BOTH real and
   marker bookings the CELL uses only the real bookings (icons, names, firm/tentative
   treatment, single-booking colour fill); marker-only dates keep the marker's own
   icon/colour. The day sheet (`bookingsOn`) still lists everything — precedence is a
   cell-rendering rule, not a data filter.
6. **Manage screen.** The add/edit dialog gains a "Used for" pill row
   (`settings.event_types.kind_*`, same FilterChip vocabulary as the booking form's
   Confirmed/Tentative) with a helper line under the marker pill; list rows show a
   subtle `booking.marker.badge` chip on marker presets.
7. **Analytics.** `EventTypeBreakdownCalculator` excludes bookings resolving to a
   marker-kind preset (count AND revenue) and reports `excludesMarkers` so the report
   detail shows the `reports.event_types.marker_note` footnote only when something was
   actually dropped. Only the event-type breakdown changes: the other money reports
   (revenue, dues, profit…) aggregate payments/amounts, which marker bookings simply
   don't have — and a marker with an accidental amount still appearing there is the
   honest behaviour.

**Consequences.** Web should mirror the precedence + exclusion rules. Old app versions
ignore the column (defaulted decode; Room untouched pre-migration). Deleting a marker
preset makes its old bookings count again — acceptable: the preset is the single source
of kind truth, mirroring how recolouring works.

## ADR-042 — Menu tab is a nested nav graph (bottom-bar highlight fix) (2026-09-02)

**Status:** accepted.

**What.** The bottom bar's selected state already used `currentDestination.hierarchy`
matching, but Reports and the app-bar Sync-status screen were registered as ROOT-level
destinations (siblings of the four tabs), so their hierarchy contained no tab route and
NO tab highlighted while on them. Fix: the Menu tab is now a `navigation()` graph
(`MENU_TAB_ROUTE`, start destination `MENU_ROUTE`) containing `menuGraph` + `reportsGraph`
+ `syncStatusGraph`; `NavPermissions`/`MainViewModel`/App-Link routing use the graph
route (navigating to a graph route lands on its start destination, and the §3
first-visible-tab / revocation redirects work unchanged). Selection is extracted into
the pure `NavTabSelection.selectedTab(hierarchyRoutes, tabRoutes)` (unit-tested) used by
both the bar and the permission-revocation currentTab probe. The other three tabs need
no graph: their subscreens live in feature-internal NavHosts, so the root destination —
and therefore the hierarchy match — never changes there; the Menu tab's own nested
NavHost subscreens (Settings/Members/About) were likewise already covered.

**Consequence.** Opening Sync status from the app-bar cloud icon on any tab now
highlights Menu — correct: it IS a Menu subscreen, and back returns to the previous tab.

## ADR-043 — Contextual notification permissions + status rows (2026-09-02)

**Status:** accepted.

**Context (honest audit).** `POST_NOTIFICATIONS` was declared in the manifest but NEVER
requested at runtime: on Android 13+ (every modern device) the permission starts denied,
`BookingNotifier`/`ConflictNotifier` silently no-op behind their `canNotify()` guards,
and every reminder notification — payment confirmations, follow-ups, upcoming-event
alerts, the full-screen alarm style — was silently dropped. Additionally, on Android 14+
the full-screen-intent grant (`canUseFullScreenIntent`) can be off for sideloaded apps
(the notification downgrades to heads-up), and on Android 12+ the exact-alarm grant can
be revoked (the alarm falls back to inexact). Only the in-app pending-confirmations card
actually worked.

**Decision (spec §6: optional, in-context, fully usable when denied).**
1. **Contextual requests, never at launch.** The system POST_NOTIFICATIONS dialog fires
   (a) after saving a booking — the first moment reminders matter — and (b) on opening
   Settings → Booking reminders. Denial changes nothing; the OS itself stops the dialog
   after repeated denials.
2. **Status rows.** Settings → Booking reminders gains a "Reminder permissions" section
   driven by the pure, unit-tested `ReminderPermissionsStatus`: a Notifications row
   (state via `areNotificationsEnabled()`, covering both the runtime permission and
   channel/app-level blocks) always; Full-screen-reminders (API 34+,
   `canUseFullScreenIntent()`) and Exact-time-alarms (API 31+,
   `canScheduleExactAlarms()`) rows only while the ALARM style is selected. Denied rows
   show the consequence in words plus an "Allow" fix button deep-linking to the matching
   system screen (`ACTION_APP_NOTIFICATION_SETTINGS`,
   `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`, `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`);
   states re-read on every resume so returning from Settings refreshes immediately.
3. **No behaviour change for denied users** — notification posting keeps its silent
   no-op guards; the in-app cards remain the reliable path (§4.1).

## ADR-044 — Markers carry no payment surface; form-open notification recheck (2026-09-02)

**Status:** accepted.

**Context.** ADR-041 made marker-kind presets (Lagan, Tilak) first-class, but marker
BOOKINGS still rendered the full payment surface: the card showed Total/Paid/Due ₹0
rows, record-payment/invoice actions, the form offered amount fields, and — in edge
cases where a marker row carried money (preset flipped to marker after creation, or a
row from an older client) — the month summary counted it and the reminder engine could
create payment reminders for it. Separately, ADR-043's contextual POST_NOTIFICATIONS
request fired only AFTER saving a booking, so a user who opened the form but had
notifications disabled learned nothing until the save moment.

**Decision.**
1. **Card.** `BookingDetail` gains `isMarker` (resolved via `EventTypeKinds.isMarker`
   against the live presets, same normalized-label contract as everywhere). A marker
   card hides Total/Deposit/Paid/Due, the payment-history section, and the
   record-payment, invoice AND WhatsApp actions — the share text is specifically a
   payment REMINDER quoting the due, meaningless for a marker. Customer, dates, status
   chip, audit line, edit and cancel remain.
2. **Summary + confirmations.** The month summary's Received/Pending exclude marker
   bookings (and their payments) entirely; the pending-payment-confirmations card
   filters marker bookings too (belt — see 4).
3. **Form.** `BookingFormState.isMarkerType` (live, from the current selection —
   including a free-text label that normalizes onto a marker preset). While true the
   form hides Total amount / Security deposit / Advance / auto-Due; invoice number,
   source, colour, notes, dates, customer and status STAY (the owner's complaint was
   amounts; an invoice number or source may still be meaningful bookkeeping). Hidden
   fields keep their typed text — switching the type back restores the values; only a
   SAVE with a marker selected forces `total`/`deposit` to 0 and skips the advance
   payment row.
4. **Reminder engine.** Marker bookings are filtered OUT of payment planning
   (`bookingsEndedBefore` minus markers) and `PaymentReminderPlanner.staleDismissals`
   gains an `isMarker` predicate, so a pending payment reminder whose booking now
   resolves to a marker is dismissed on the next daily/post-sync pass. UPCOMING-event
   reminders still fire for markers — a Lagan day is exactly the kind of event the
   owner wants to be reminded of. Agenda/events rows never had a payment chip (they
   show the booking STATUS), and money reports aggregate amounts/payments that markers
   don't have (ADR-041 §7) — both verified, no change.
5. **Notification recheck on form OPEN (moves ADR-043's request point).** Opening the
   add/edit form re-checks POST_NOTIFICATIONS via the pure, unit-tested
   `NotificationPermissionGate`: prompt when API 33+, notifications disabled, and
   (never requested before OR `shouldShowRequestPermissionRationale` is true — i.e. a
   system dialog can still actually appear). A per-device "requested once" flag
   (`booking_notification_permission_requested`, shared settings DataStore via
   `NotificationPromptPrefs`) disambiguates Android's tri-state rationale: requested
   before + no rationale = permanently denied → stay silent forever; the Settings →
   Booking reminders status rows (ADR-043 §2) remain that user's fix path. The
   post-save request is REMOVED (the same form was just open — asking twice in one
   flow is the nagging §6 forbids).

**Consequences.** A marker with legacy money keeps its payment rows in Room (nothing is
deleted); they are simply invisible and uncounted until the type changes back — then
everything reappears, mirroring ADR-041's "the preset is the single source of kind
truth". Web should mirror the same card/summary/reminder exclusions.

## ADR-045 — Reminder style honored by every reminder kind + Test button (2026-09-03)

**Status:** accepted.

**Context (the "full-screen popup shows a normal notification" report).** Three
candidate causes were investigated on an API 35 emulator:

- **(a) Android 14+ full-screen-intent grant.** When `canUseFullScreenIntent()` is
  false the OS silently demotes the full-screen notification to a heads-up. ADR-043's
  status row + deep link exist and work, but the row was quiet (small error-colored
  caption + text button) and nothing warned at the moment of demotion.
- **(b) Android DESIGN, not a bug.** While the device is ON and UNLOCKED the system
  deliberately shows a full-screen-intent notification as a heads-up banner; the
  activity takes over only when the screen is off/locked. An owner testing with the
  phone in hand always sees "a normal notification" — expectation mismatch.
- **(c) Style not applied at fire time.** REAL BUG: only the UPCOMING-EVENT pass read
  `booking_reminder_style`; payment confirmations and tentative-booking follow-ups
  ALWAYS posted plain notifications regardless of the selected style. An owner who
  chose "Full-screen popup" and then received a (payment) reminder as a plain
  notification had no way to tell the kinds apart.

**Decision.**
1. **Every reminder kind honors the style.** `ReminderEngine.runDailyPass` resolves
   the prefs ONCE and passes style+sound to all three passes.
   `postPaymentReminder`/`postFollowUpReminder` take `style`/`soundUri` (no defaults —
   the compiler forces every call site to decide) and, for FULLSCREEN, attach a
   full-screen intent to a generalized `FullScreenReminderActivity` (new body-text
   variant) with MAX priority, ALARM category and the chosen sound (per-sound payment
   channel variant, same immutable-channel workaround as upcoming). Payment/follow-up
   full-screen notifications post directly from the 09:00 pass — no extra alarm hop —
   while upcoming keeps its exact-alarm path.
2. **Cross-feature test-fire contract (additive).** New `core:data` interface
   `reminders.ReminderTestFirer` — implemented by `feature:booking`'s
   `BookingReminderTestFirer`, consumed by `feature:menu` (same pattern as
   `SyncStatus`). A "Test" button beside the style selector fires ONE sample reminder
   through the EXACT production pipeline: Simple → `postUpcomingReminder`; Full-screen
   → `UpcomingReminderAlarmReceiver.scheduleExactAt(now + 3 s)` so the sample travels
   the identical AlarmManager → receiver → full-screen-notification path (the 3 s also
   let the owner lock the screen and see the true takeover).
3. **No silent demotion on test.** Pure `ReminderPermissionsStatus.blocksFullScreenTest`
   gates the button: full-screen style + API 34+ + grant off → a fix-it dialog
   deep-linking to `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` instead of a test that
   quietly lies.
4. **Expectation-setting copy + loud rows.** A localized hint under the style options
   states the system behavior (screen on → banner; screen off/locked → takeover), and
   DENIED permission rows are loud: error-container background, warning icon, filled
   Allow button.

**Consequences.** The full-screen style now means "alarm-style for ALL booking
reminders", matching what the owner expected. Post-sync re-posts keep
`setOnlyAlertOnce`, so style changes re-render quietly. Verdict on the report:
(c) fixed in code; (b) documented in-product; (a) made loud and test-guarded.

## ADR-046 — Dedicated "Samaroh" calendar, prompt calendar pushes, duplicate repair (2026-09-06)

**Status:** accepted. Supersedes ADR-015's "primary calendar" decision; extends the
frozen sync contract additively (`LocalMutationListener`).

**Context (three owner reports).**
1. *Calendar events lag hours behind booking changes.* The only calendar triggers were
   a 6-hour periodic and an enable-time one-shot; booking mutations never enqueued the
   calendar worker (the ADR-036 debounce only fed the DATA sync).
2. *Events land on the primary calendar.* ADR-015 chose `primary` because
   `calendar.events` cannot create calendars. Google now offers
   `https://www.googleapis.com/auth/calendar.app.created` — "Make secondary Google
   calendars, and see, create, change, and delete events on them" (scope table, updated
   2026-08) — which removes exactly that blocker with least privilege.
3. *An edit produced a second event.* Root causes found in the old engine:
   - the planner keyed ONLY on the device-local fingerprint store and ignored the
     synced `bookings.gcal_event_id`; any state miss (sign-out wipe ADR-040, reinstall,
     an interrupted pass, a second device) re-CREATED already-pushed events;
   - the state-store write in the engine's `finally` ran in a cancelled coroutine
     context when WorkManager cancelled the worker (REPLACE/constraint loss), throwing
     and LOSING the just-pushed event ids;
   - the enable-time one-shot and the periodic's immediate first run could execute the
     engine concurrently over the same empty state (double bulk-push), and
     disable-cleanup shared the one-shot's unique work name, so a pending cleanup
     silently swallowed (KEEP) the enable-time push — the "enable did nothing" race.

**Decision.**
1. **Prompt pushes via the outbox path.** New `core:data` contract
   `LocalMutationListener` (multibound set, notified by `RoomOutboxWriter` after every
   enqueue — same moment as the ADR-036 debounce nudge; listener failures never fail
   the write). `core:google` contributes `BookingMutationCalendarTrigger`: mutations of
   `bookings`/`booking_payments` for a gcal-enabled business enqueue a DEBOUNCED
   calendar one-shot (3 s, REPLACE, own unique work name). Create/edit/cancel/delete
   and payment changes now reach Google Calendar within seconds.
2. **Dedicated calendar.** Link-time scopes become `drive.file` + `calendar.events` +
   `calendar.app.created`. With the new scope granted, the engine finds-or-creates the
   "Samaroh" calendar: the SYNCED `google_accounts.calendar_id` is the cross-device
   registry (the scope has no calendar-list access); a cached id is verified and
   re-created if the user deleted the calendar. On the first pass after the grant the
   engine MIGRATES recorded primary-calendar events via `events.move` (ids preserved;
   delete+recreate fallback on 403) and deletes stray app-created events left on
   primary — matched by the new extended-property marker or the localized
   "Managed by Samaroh" description line (en+hi), never touching other events.
   Accounts still on the legacy grant keep pushing to primary (graceful fallback); the
   silent token fetch falls back to the legacy scope set, and Settings shows a
   localized re-link hint (`settings.gcal.relink_hint`).
3. **Duplicate-proofing.** Every pushed event carries private extended properties
   (`samarohBookingId`, `samarohManaged=1`). The planner ADOPTS a booking whose state
   entry is missing but whose synced `gcal_event_id` is set — planned as an update of
   that event, never a create; an update whose event vanished (404/410) re-creates it.
   Engine passes are serialized by an in-process mutex; the state store write is
   `NonCancellable`; disable-cleanup got its own unique work name and enabling cancels
   any pending cleanup. A repair pass (only on passes that created/adopted) lists
   managed events by extended property and deletes any second event claiming an
   already-recorded booking.

**Consequences.** `google_accounts.calendar_id` changes are outbox-synced (they always
were on link; now also when the engine sets them). The engine no longer checks
`GoogleServicesConfig.isConfigured` (the worker gates it) so it stays unit-testable.
Owner action required once per Google Cloud project: add `calendar.app.created` under
Google Auth Platform → Data Access, then re-link in Settings.

## ADR-047 — Rich calendar event description, PATCH updates, remote-pull calendar trigger (2026-09-06)

**Status:** accepted. Extends ADR-046 additively (`RemoteChangeListener` in the frozen
sync contract; `GcalEventMapper` description/fingerprint format).

**Context (three owner reports).**
1. *The event description shows only amounts.* The owner wants the booking's full
   picture in Google Calendar — customer, phone, event type, status, all amounts,
   invoice number, source and notes — without opening the app.
2. *Everything is on one line.* The old `settings.gcal.event_description` key rendered
   `Total · Paid · Due` in a single line.
3. *Another member's edit doesn't reach my calendar.* The ADR-046 trigger rides the
   OUTBOX (`LocalMutationListener`), which only local mutations touch. A booking edited
   by another member arrives via the sync PULL (`LocalApplier` writes DAOs directly) and
   the calendar only caught up on the 6-hour periodic.

**Decision.**
1. **Rich multi-line description.** `GcalEventMapper.description` renders one field per
   line — customer name, phone (when set), event type, status, total, security deposit,
   advance paid, due, invoice number (when assigned), source (when set), notes
   (verbatim, LAST before the footer) and the localized "Managed by Samaroh" footer.
   Labels reuse the existing `booking.*` catalog keys; the only new key is
   `settings.gcal.description_line` (`"{label}: {value}"`). Amounts render via
   `AmountFormatter` (Indian grouping, ADR-002). Locale = the app's current locale at
   push time.
2. **One-time re-push via a fingerprint format version.** The push fingerprint now
   includes every description-visible field (phone, invoice number, source, notes) plus
   a `FORMAT_VERSION` salt. Bumping the version invalidates every stored fingerprint at
   once, so the next pass re-pushes each recorded event exactly ONCE — as an UPDATE of
   the recorded event id (planner update path / ADR-046 adoption), never a create, so
   the format migration cannot duplicate events.
3. **Updates PATCH.** `RestCalendarService.updateEvent` sends PATCH (via
   `X-HTTP-Method-Override` on POST — `HttpURLConnection` cannot send PATCH natively)
   instead of PUT: a full-resource PUT clears writable fields the body omits (user-set
   reminders, colour); PATCH touches only the app-owned fields. The event body nulls
   the unused start/end date variant explicitly so timed ↔ all-day switches still clear
   the stale pair under PATCH semantics.
4. **Remote-pull calendar trigger.** New additive `core:data` contract
   `RemoteChangeListener` (multibound set, `@Multibinds` default empty): after a sync
   run whose pull applied rows, `SyncEngine` reports the applied business-scoped tables
   as `table → business ids`; listener failures never fail the run. `core:google`
   contributes `RemoteBookingCalendarTrigger`: applied `bookings`/`booking_payments`
   for a gcal-enabled business enqueue the SAME debounced calendar one-shot as the
   local trigger. Remote edits now reach the calendar within one sync cycle.

**Loop convergence (the gcal_event_id write cycle).** The calendar engine records
pushed event ids on the booking row (`recordEventId` → outbox UPSERT). That write
re-enters BOTH triggers: locally via `LocalMutationListener`, and — after the server
echoes the row — via the pull → `RemoteChangeListener`. The cycle converges because the
fingerprint deliberately EXCLUDES `gcal_event_id` and the audit timestamps: the
follow-up pass plans empty and returns before any network call or write, so no new
mutation is produced and the chain stops (one free no-op pass per echo, debounce
collapses bursts). Guarded by `CalendarSyncEngineTest's convergence test` (bounded-runs assertion)
and a mapper test pinning fingerprint stability across `gcalEventId` changes; a comment
on `GcalEventMapper.fingerprint` forbids adding either field.

**Consequences.** Existing events get one PATCH re-push with the new description after
this build first syncs. `settings.gcal.event_description` is no longer referenced by
Android (kept in the catalog for the web track). The sync engine's constructor gains a
multibound set — Hilt injects it automatically; tests default it empty.

## ADR-048 — Per-business calendar named after the business (2026-09-06)

**Status:** accepted. Refines ADR-046's dedicated-calendar decision; additive column on
`business_settings` (frozen contract; shared baseline + `scripts/alter-gcal-calendar-id.sql`).

**Context (owner report).** The dedicated calendar is named "Samaroh" (a localized
catalog key). The owner wants it named after HIS BUSINESS ("Four Season Marriage Hall")
— the calendar represents the venue, not the app. Structurally, calendar sync is
enabled PER BUSINESS (`business_settings.gcal_sync_enabled`) but ADR-046 stored the
calendar id PER USER (`google_accounts.calendar_id`): one calendar per Google account,
shared by every business — wrong unit, and no way to give two businesses two names.

**Decision.**
1. **Registry moves to the business.** New synced column
   `business_settings.gcal_calendar_id` (Room v8 `MIGRATION_7_8`; shared baseline
   001 edit for fresh databases + owner-run `scripts/alter-gcal-calendar-id.sql` for
   the live one). `google_accounts.calendar_id` is DEPRECATED as a push target: it
   remains the no-scope primary fallback, the legacy-adoption source, and a mirror
   (first business only) so servers without the new column still sync a registry.
   Until the owner applies the alter, PostgREST rejects `business_settings` pushes
   (PGRST204) — held per-item and retried, the ADR-027/030 self-healing pattern;
   pulls select `*` and decode via the defaulted model field.
2. **Find-or-create per enabled business; name = business name.** The engine resolves
   the target per business: registered id → verify + keep; else adopt the legacy
   ADR-046 calendar (first business to sync claims it — an id already claimed by
   another business is not stolen; that business creates its own and MIGRATES its
   recorded events over, `events.move`, no stray cleanup on a shared source); else
   create fresh (+ ADR-046 primary migration). Blank business name falls back to the
   localized "Samaroh".
3. **Renames.** The owner's existing "Samaroh" calendar is renamed to the business
   name via `calendars.patch` — VERIFIED authorized under `calendar.app.created`
   (Calendars: patch scope table). A 403 falls back to create-new + migrate (ADR-046
   shape). Later business-name edits re-title the calendar: `businesses` joins both
   calendar triggers (outbox + remote pull, the row id IS the business id — the sync
   engine now also reports applied `businesses` rows to `RemoteChangeListener`s), and
   the engine checks a device-local last-set-name cache (`GcalSyncStateStore`) so a
   no-op pass still makes ZERO calendar HTTP (the ADR-047 free-echo invariant holds);
   a stale cache costs one GET+PATCH pass. Multi-business: every enabled business gets
   its own calendar, each carrying its own name.

**Convergence.** The engine's `gcal_calendar_id` write goes through the outbox and
echoes back like any row, but neither trigger lists `business_settings`, so registry
writes never re-fire a pass (tested); the rename pass converges because the name cache
equals the business name after one PATCH (tested: rename happens once, third pass free).

**Consequences.** Owner action once: run `scripts/alter-gcal-calendar-id.sql` in the
Supabase SQL editor (until then the registry also rides the google_accounts mirror).
`settings_gcal_calendar_name` stays as the blank-name fallback only.

## ADR-049 — Attachment-import fix, real link-Google prompt, calendar skip when unlinked (2026-09-06)

**Status:** accepted. Field-evidence-driven (owner phone capture 2026-09-06); additive —
no frozen-contract changes.

**Context (field evidence).** (a) EVERY image attachment failed with "Couldn't add that
file" and ZERO logcat: `AttachmentCompressor.compressImage` null-checked the return of
the `inJustDecodeBounds` pass, which is null BY CONTRACT on device — the elvis fired on
every image (gallery AND camera). Unit tests stayed green because legacy Robolectric
shadows return a Bitmap from that pass. (b) The post-save "connect Google" dialog was a
stub: link status hardcoded `false`, Connect just dismissed. (c) On a device whose
signed-in user has no local Google link, EVERY booking mutation burned the calendar
worker's full 5 retries on `DriveNotAvailableException` — a permanent local state, with
a stack trace per attempt.

**Decision.**
1. **Bounds pass never null-checks the decode result** (only the stream open); prepare
   failures now leave a `SamarohAttach` logcat breadcrumb. Compressor/add-entry tests run
   under `@GraphicsMode(NATIVE)` so Robolectric decodes like a device — the faithful
   regression for this whole class of bug.
2. **`feature:expenses` gains a `core:google` dependency** (precedent: `feature:menu`)
   to complete the §4.2 prompt: `AddEntryViewModel` reads real
   `GoogleAccountLinker.linkState` (prompt only on `NotLinked`; `NotConfigured` finishes
   quietly), and Connect runs the full link flow in place — account picker, incremental
   scope-consent sheet, then a data-sync nudge so queued uploads start immediately.
   The ledger's pending badge switches to a "link your Google account" hint (CloudOff)
   while unlinked. New keys `expenses.ledger.pending_upload_unlinked`,
   `expenses.google_prompt.link_failed`.
3. **Calendar worker verdict semantics:** `DriveNotAvailableException` (not signed in /
   no link row / no silent token) maps to a quiet SUCCESS-skip (one info line), not
   retry/failure — retrying cannot succeed, and both link flows kick a fresh push on
   success while the 6-hour periodic covers the rest. Transient faults keep
   retry-with-backoff up to 5 attempts (`CalendarSyncWorker.resolveFailure`, unit-tested).

**Consequences.** Attachments import again (root cause of the field bug); unlinked users
get an actionable path to Drive storage instead of a dead-end dialog; booking mutations
on unlinked devices cost one log line instead of five stack traces. Emulator-verified:
booking create/cancel → debounced push → skip → `Worker result SUCCESS`; remote pull
trigger unchanged.

## ADR-050 — Shared image-compression levels, EXIF-upright attachments, honest attachment errors (2026-09-06)

**Status:** accepted. Owner-directed picker/compression upgrade; additive — no
frozen-contract changes. Extends ADR-025 (cropper) and ADR-049 (attachment fix).

**Context.** Four call sites each hard-coded their own scale-and-encode: expense
attachments (JPEG 85/2048, `AttachmentCompressor`), inventory item photos (WebP 80/320,
`ItemImageStore`), the onboarding logo (`LogoProcessor`) and a fourth, INLINE duplicate
in `BusinessProfileViewModel`. The owner wants compression to be a per-use-case
argument: **~10% compression for invoice/bill attachments** (they must stay readable)
and **~50% for inventory item images** (rendered as small thumbnails). Separately, the
attachment image path decoded without EXIF rotation (camera JPEGs stored sideways —
the gap ADR-025 fixed only for the cropper), errors collapsed "too large" into
"couldn't read", and a missing camera app crashed the capture buttons.

**Decision.**
1. **One shared pipeline in `core:designsystem`** (`imaging/`): `CompressionSpec`
   (quality, longest-side cap, JPEG/WebP) + `ImageCompression` (power-of-two subsample →
   exact scale → encode; never upscales) + `decodeUprightImage` (bounded two-pass decode
   + EXIF upright rotation, reporting `rotationDegrees`/`wasDownsized`). The cropper's
   `loadCropSourceBitmap` is now a thin wrapper over the same decoder. Precedent for
   non-composable shared code in designsystem: the ADR-025 cropper geometry.
2. **Levels are arguments, named per use-case.** The owner's "~N% compression" maps to
   encoder `quality = 100 − N`; dimension caps are per use-case and unchanged:

   | Use case | Spec | Quality | Max side | Format |
   |---|---|---|---|---|
   | Invoice/bill image attachment | `DocumentLight` | 90 | 2048px | JPEG |
   | Inventory item photo | `ItemPhoto` | 50 | 320px | WebP |
   | Business logo (onboarding + settings) | `Logo` | 85 | 320px | WebP |
   | PDF attachment | — | copied byte-identical (bounded) | — | — |

   Interpretation: for item photos the ≤320px WebP dimensions already do the heavy
   byte-saving, so the ~50% level is applied as ENCODER QUALITY 50 at those dimensions
   (unit test pins the ordering: item output < a light encode of the same pixels).
   Invoices go from quality 85 → 90 (lighter touch than before — readability first).
   The logo keeps its existing treatment; `BusinessProfileViewModel`'s inline duplicate
   now rides the same shared call.
3. **EXIF everywhere.** `AttachmentCompressor` decodes through `decodeUprightImage`,
   so camera/gallery invoices store upright (regression test writes a rotated JPEG and
   asserts swapped output dimensions). Item photos/logos were already upright via the
   cropper's decode; that decode is now the same code path.
4. **Keep-original-when-smaller.** When re-encoding an image would not shrink it (an
   already-efficient small image) AND nothing had to change (no downsize, no rotation),
   the original bytes are copied verbatim with their original mime — attachments never
   get bigger or lossier for no reason.
5. **Honest attachment errors + bounded copies.** Document (PDF) attachments are
   size-checked up-front via the provider-declared size and streamed through a bounded
   copy (25 MB cap, memory O(buffer)); over-cap picks return `TooLarge`, rendered as a
   dedicated snackbar (`expenses.entry.attach_too_large`) distinct from `Unreadable`
   ("couldn't add"). Capture buttons catch `ActivityNotFoundException` and show
   `common.camera_missing` instead of crashing on camera-less devices.
6. **Crop policy unchanged, now explicit.** Item photos and the business logo keep the
   ADR-025 interactive SQUARE crop (their storage/rendering is square). Invoices/bills
   are documents: they attach DIRECTLY with no crop step — not even an optional one —
   because readability is the point and the preview thumb + remove affordance already
   covers "wrong pick". Square-cropping a bill destroys it; a free-form crop editor is
   scope without evidence of need.

**Consequences.** Any future image use-case picks (or adds) a named `CompressionSpec`
instead of copy-pasting encode code; quality/size questions are answered in one file.
Attachment imports are upright, honestly refused when oversized, and never bloated by a
pointless re-encode. Tests for the levels/EXIF/crop-policy run under
`@GraphicsMode(NATIVE)` (the ADR-049 lesson — legacy shadows lie about codecs). Stored
formats and the sync/Drive mirroring contracts (ADR-023) are unchanged.

## ADR-051 — Sync-noise fixes: no-op re-apply guard, calendar cancellation semantics (2026-09-06)

**Status:** accepted. Field-evidence-driven (owner phone logcat + Room capture
2026-09-06); additive `byId` DAO queries on the frozen `core:database` contract.

**Context (two live-log defects).**
1. *"debounced calendar push requested" on EVERY sync run.* Filtered logcat showed the
   line firing on every run alongside `pushed=0 pulled=11 itemErrors=1` (the held
   business_settings PGRST204 item, ADR-048 pending alter). The push retry itself was
   innocent — push failures/rewrites go through `OutboxDao` directly, never
   `RoomOutboxWriter`, so `LocalMutationListener`s never see them, and neither calendar
   trigger lists `business_settings`. The real leak was the PULL: Room persists the
   keyset cursor (`sync_cursors.last_pulled_at`) as epoch MILLIS while Postgres
   `timestamptz` compares at microseconds, so `updated_at > cursor` re-matched every
   table's boundary row on every run (≈11 tables ⇒ `pulled=11` forever). Each re-served
   identical row counted as "applied", so `appliedTables` always contained
   `bookings`/`businesses` and `RemoteBookingCalendarTrigger` enqueued a calendar push
   per sync run; the spurious "applied" count also re-ran every `PostSyncHook`.
2. *"calendar sync attempt 0 failed" + `JobCancellationException` WARN.* The on-change
   one-shot used REPLACE: a new debounced request cancelled an in-flight worker, and the
   engine's `runCatching` captured the coroutine's `CancellationException` into its
   `Result`, which `resolveFailure` WARN-logged and retried as if the pass had failed —
   noise plus a wasted retry, against structured-concurrency rules.

**Decision.**
1. **One timestamp precision — millis.** `WireConverter.parseTimestamp` truncates to
   millis (the precision Room stores); in-memory entities, LWW comparisons and cursors
   now agree with persistence.
2. **No-op re-apply guard.** `LocalApplier.apply` returns whether the row CHANGED Room:
   the incoming entity is compared (data-class equality) against the stored row and an
   identical row skips the write. The engine counts only changed rows as applied, so
   boundary re-pulls no longer feed `appliedTables`, the remote-change listeners or the
   post-sync hooks. The boundary rows are still re-FETCHED each run (≤1 per table,
   cheap, self-limiting) — fixing that would need sub-ms cursor storage (schema change)
   for no observable win. Additive `byId` lookups added to the seven DAOs that lacked
   one (frozen contract, additive per ADR-001).
3. **Cancellation is not failure.** `CalendarSyncEngine.syncBusiness`/`disable` rethrow
   `CancellationException` out of `runCatching` (`rethrowCancellation()`), and
   `CalendarSyncWorker.resolveFailure` rethrows it defensively — a cancelled pass is
   recorded by WorkManager as cancelled, never WARN-logged or retried. The
   `NonCancellable` state-store write (ADR-046) still persists partial progress first.
4. **On-change policy REPLACE → APPEND_OR_REPLACE.** An in-flight calendar pass now
   completes and the new request chains after it instead of being killed mid-run
   (ADR-046's mutex already serializes engine passes; the chained follow-up plans empty
   and makes zero HTTP per the ADR-047 free-echo invariant). A failed/cancelled
   predecessor is replaced, not inherited. The 3 s initial delay still debounces bursts
   while the request is pending.

**Consequences.** Steady-state sync runs report `pulled=0` and stay silent: no calendar
one-shot churn, no spurious post-sync hook runs, no cancellation WARNs. The held
business_settings item keeps retrying per ADR-048 until the owner applies the alter —
now without side effects. Genuine local mutations, remote edits and business renames
still trigger the calendar push exactly as before.

## ADR-052 — View expense attachments: source resolution + Drive media download (2026-09-06)

**Status:** accepted. Additive on three frozen contracts (ADR-001 process):
`ExpenseAttachmentDao.updateLocalCachePath`, `ExpensesLedgerRepository.updateAttachmentLocalCachePath`,
`DriveService.downloadFile`.

**Context.** Ledger rows render attachment thumbnails but nothing opened them. The bytes
live in Google Drive (the authoritative store, §4.2); the device may or may not hold a
local cache copy (`expense_attachments.local_cache_path`, Room-only state): a row synced
from another device has a `drive_file_id` but no local file, and a row created offline on
this device has a local file but no `drive_file_id` yet.

**Decision.**
1. **`AttachmentContentResolver`** (feature:expenses, plain class provided by
   `AttachmentsModule`) resolves a tapped attachment in priority order:
   (1) live `local_cache_path` file → open directly; (2) `drive_file_id` + Google linked →
   download `files.get?alt=media` with the current user's token, cache into the
   compressor's attachments dir (`files/expense_attachments`, already covered by
   `expenses_file_paths.xml`), stamp the row's `local_cache_path` (NO outbox op —
   device-only state), then open; (3) `drive_file_id` but NOT linked →
   `NeedsGoogleLink`; (4) neither → `NotAvailable`. Download failures (typically
   offline) map to `DownloadFailed` and a friendly localized retry message; the partial
   file is deleted. The download target name is deterministic (`drive-{id}-{fileName}`)
   so retries overwrite instead of piling up partials.
2. **Drive media download** reuses the existing REST plumbing: `DriveService.downloadFile`
   → `GoogleApiHttp.downloadToFile`, a streaming GET that writes the binary body straight
   to disk (`request()` buffers text and would corrupt media bytes). Same
   `GoogleAccessTokenProvider` silent-token path as uploads; `drive.file` scope covers
   files the app created.
3. **Open UX (party ledger).** Images → full-screen in-app `Dialog` viewer (the inventory
   tap-to-expand pattern); PDFs → `ACTION_VIEW` chooser through `ExpensesFileProvider`,
   with a manifest `<queries>` block so a missing PDF viewer is detected honestly and
   rendered as a localized snackbar instead of an empty system chooser. While a Drive
   download runs, the tapped thumbnail shows a scrim + spinner
   (`openingAttachmentId`); one resolution at a time. The `NeedsGoogleLink` case shows a
   link-to-view dialog that runs the §4.4 link flow in place (same consent plumbing as
   the add-entry prompt) and re-opens the tapped attachment on success, plus nudges sync
   so this device's pending uploads move too.

**Consequences.** A downloaded file becomes the local cache — the thumbnail upgrades from
the generic icon to the image preview reactively (Room flow re-emits on the
`local_cache_path` update). Cache-path stamping deliberately bypasses the outbox: the
column never syncs. There is no separate entry-detail surface; the ledger rows are the
attachment surface (add/edit only stages new local files).

## ADR-053 — Image-viewer actions + user-tunable compression quality (2026-09-06)

**Status:** accepted. Owner-directed; additive only (new DataStore keys, new shared
component, constructor extensions on non-frozen classes). Extends ADR-050 (compression
levels) and ADR-052 (attachment viewer/resolver).

**Context.** The ADR-052 image viewer was a bare tap-to-close dialog: no way to save a
bill to the device, no way to delete a wrong bill without deleting the whole entry, and
no distraction-free view. Separately, the ADR-050 compression levels were fixed
constants; the owner wants the squeeze tunable per use case from Settings.

**Decision.**
1. **One shared viewer** — `ImageViewerDialog` (`core:designsystem/component`), used by
   the expenses ledger AND both inventory expand dialogs (stock list + item detail).
   Actions are `ExplainableIcon`s in a top chrome bar:
   - *Full screen*: immersive mode on the DIALOG's own window (system bars + chrome
     hidden); tapping the image restores. Outside immersive mode a tap still CLOSES the
     viewer — the pre-existing expand-dialog gesture.
   - *Download*: copies the shown file into public Downloads via `MediaStoreImageSaver`
     — `MediaStore.Downloads` + `IS_PENDING` on API 29+ (no permission), legacy
     public-dir write + scan behind a runtime `WRITE_EXTERNAL_STORAGE` request on 26–28
     (`maxSdkVersion="28"`). Success snackbar names the location (`Download/Samaroh`).
   - *Delete* (optional `ImageViewerDeleteAction`): confirmation dialog, semantics owned
     by the feature.
2. **Expenses delete cascade** (`AttachmentDeleter`): tombstone the
   `expense_attachments` row + outbox DELETE (authoritative), remove the local cache
   file, then BEST-EFFORT Drive `files.delete` (the pre-existing `DriveService.deleteFile`;
   `drive.file` scope covers app-created files) only when a `drive_file_id` exists and
   Google is linked. A Drive failure is logged and swallowed — the metadata tombstone
   wins on every device; the orphan merely lingers in the owner's Drive. Gating follows
   the entry-row convention: the action shows for `expenses.delete` OR `expenses.edit`.
   The viewer closes on delete; ledger thumbnails drop reactively via the Room flow.
3. **Inventory viewer scope: fullscreen + download ONLY.** Download is offered only when
   the photo resolves to a LOCAL file (a remote Storage photo is an authenticated Coil
   request, not bytes on disk — saving it would mean a second download pipeline for no
   demonstrated need). Delete is NOT in the inventory viewer: clearing an item's photo
   is a masterlist EDIT (existing "Remove photo" in the editor dialog, with its own
   permission gate); the expand dialog also appears in read-only stock contexts, and
   wiring a photo-clear from there would duplicate the editor's save path rather than
   compose with it.
4. **Compression quality is a preference** (`ImageQualityPreferences`, `core:data`, keys
   `image_quality_bills` / `image_quality_item_photos` in the shared `"settings"`
   DataStore). Settings → *Image quality* offers three plain-language chips per use
   case with a size hint; the chip values map to encoder qualities —
   bills high/balanced/low = 90/75/60 (band 60–100, default 90), item photos = 75/50/30
   (band 30–90, default 50). Values are coerced into the band on read AND write, so a
   corrupt store can never produce an unreadable bill. **Max dimensions and formats stay
   fixed per ADR-050** — only `quality` is user-tunable.
5. **Call sites read the pref, not a constant.** `AttachmentCompressor` takes a
   `specProvider` resolved at prepare time (`DocumentLight.copy(quality = pref)`), and
   `LocalItemImageStore` injects the preferences (`ItemPhoto.copy(quality = pref)`) —
   a Settings change applies to the NEXT encode, no restart. Defaults preserve ADR-050
   behavior byte-for-byte when the keys are unset.

**Consequences.** Bills can be exported to the device and deleted individually; a wrong
bill no longer forces deleting the entry. The viewer is one component, so future image
surfaces inherit the actions. Compression is owner-tunable within safe bands;
`SamarohAttach` byte logs make the effect observable. Drive-side deletion is
opportunistic by design — devices that never link simply leave the file, exactly like
the pre-existing party-cascade behavior (ADR-028).

## ADR-054 — Cancelled booking card: Restore + permanent Delete (2026-09-07)

**Status:** accepted. Feature-module change only; no frozen-contract edits (the
existing `BookingRepository.deleteBooking` tombstone path and `countBookingsOn`
conflict query are reused as-is).

**Context.** Opening a CANCELLED booking (events/agenda view, day sheet, month grid —
anywhere the card opens) still offered "Cancel booking" plus the full active-action
surface (edit, record payment, invoice, WhatsApp payment reminder). Cancelling an
already-cancelled booking is a no-op wearing a destructive label, and recording a
payment or sending a payment reminder against a cancelled event is nonsense. There was
also no way OUT of the cancelled state: no un-cancel, and no way to permanently remove
the dead record.

**Decision.**
1. **Action matrix is a pure function.** `BookingCardActions.forBooking(status,
   isMarker, permissions…)` derives every card action's visibility; the sheet renders
   exactly that state. Cancelled cards show ONLY Restore + Delete; active cards keep
   the pre-existing rules (including the ADR-041/044 marker money blackout). Edit,
   record-payment, invoice and the WhatsApp payment-reminder are all HIDDEN on a
   cancelled card — a cancelled booking has no editable future, no collectible dues.
2. **Restore → CONFIRMED, gated `booking.edit`.** The pre-cancellation status is NOT
   recoverable — `cancelBooking` overwrites `status` in place and no history column
   exists (adding one would break the frozen `core:model`/`core:database` contract for
   marginal value). Confirmed is the deliberate landing state: it is the app-wide
   default status and matches the tentative-follow-up "Confirm booking" precedent. The
   web track (shared `web-booking` fragment) landed the same semantics concurrently.
3. **Restore conflict warning — non-blocking, form-consistent.** If the booking's date
   range meanwhile gained other live bookings, an AlertDialog warns first (max per-day
   `countBookingsOn` across the range, mirroring the add-form's `conflictCount`; the
   cancelled booking itself is excluded by the query's `status != 'cancelled'` filter).
   "Restore anyway" proceeds — halls can host multiple events (§4.1), so restore is
   never blocked.
4. **Calendar re-push rides the existing mutation trigger.** Restore is a plain
   `saveBooking` → outbox UPSERT → `BookingMutationCalendarTrigger` → calendar
   one-shot. At cancel time the engine deleted the event AND cleared `gcalEventId`
   (ADR-051), so the planner sees no pushed state and plans a CREATE — the event
   reappears with a fresh id (logcat `SamarohGcal: created event …`).
5. **Delete = permanent tombstone, gated `booking.delete`, always confirmed.** Reuses
   `deleteBooking` (sets `deleted_at`, enqueues outbox DELETE): the booking leaves
   every list, calendar cell, agenda row and — via sync — every device. The
   confirmation dialog says exactly that ("This will permanently remove the booking on
   every device.", localized). No gcal residue: the event was already deleted at
   cancel time, and the planner's delete-on-vanish branch backstops any leftover state
   entry when the tombstone push triggers one more pass.

**Consequences.** The cancelled card is honest: two actions, both labeled with their
real effect. Restore/Delete visibility is unit-tested as a status × permission matrix
(`BookingCardActionsTest`); restore status/conflict logic and delete tombstoning are
ViewModel-tested. Strings live in the shared `booking` fragment (en + hi); the
duplicate keys the web track added concurrently were converged into the canonical
`booking.card.*` set in the same shared commit.

**Addendum (2026-09-07) — visual differentiation.** Owner feedback: Restore and Delete
rendered as twin text buttons and read as interchangeable. Per M3 conventions the two
now differ in both color and container weight: **Restore** is a filled `Button` with
`containerColor = SamarohTheme.semanticColors.moneyIn` (the ledger "You got" green
precedent — a semantic token, so it stays green even under Material You dynamic
color), and **Delete permanently** is an `OutlinedButton` with error content color and
an error border. The delete confirmation dialog's confirm action was already
error-red; the *cancel-booking* confirmation dialog's confirm action was aligned to
the same error color (the trigger button on active cards already was). Styling only —
action visibility, permissions and behavior are unchanged.

## ADR-055 — Item photos mirror to Drive as the durable copy (2026-09-07)

**Status:** accepted. Owner-directed; additive (new seam in `core:data`, new
`core:database` query, one new SyncEngine pass). Extends ADR-023 (item-image Storage
mirroring) and reuses the ADR-018 attachment queue/trigger pattern in spirit.

**Context.** Item photos upload to Supabase Storage (`inventory-images`) during the
outbox drain (ADR-023) — that is the SERVING source shared with the web app. Expense
attachments, by contrast, live durably in the owner's Drive (§2/§4.2). Item photos had
no durable copy: `master_items.drive_image_id` existed end-to-end (Room, model, sync
mappers, backup manifest) but nothing on Android ever set it.

**Decision.**
1. **Drive is the durable copy; Storage stays the serving source.** After a photo's
   Storage upload has succeeded, the device that took it ALSO uploads it to
   `Samaroh/{Business}/images/inventory/{item-name}-{item-id}.webp` (§9.1;
   `DriveTarget.InventoryImages` existed already) and stamps the file id into
   `master_items.drive_image_id` (Room + outbox upsert, so it syncs and the ADR-023
   backup manifest — which already reads `drive_image_id` — can reference it).
2. **A derivable pending set, not a queue table.** `ItemPhotoDriveMirror` (seam in
   `core:data`, implemented by `core:google`'s `DriveItemImageMirror`, consumed
   `Optional` by `core:sync` — the exact `AttachmentUploader` wiring shape). Pending =
   live row with `drive_image_id IS NULL` (new additive `MasterItemDao`
   query) whose `image_path` is already a STORAGE object path and whose device-local
   `{itemId}.webp` file still exists. Web-added photos have no local file → only the
   device that took a photo mirrors it.
3. **Silent, non-blocking, retried every sync run.** `SyncEngine.runSync` calls
   `mirrorPending()` AFTER the outbox push (so the Storage upload of a new photo has
   already happened inside the push) and drains the stamped row upserts with a second
   push in the same run. Unlike the attachment upload, the mirror NEVER blocks a
   `master_items` push and never fails a sync (wrapped `runCatching`): not linked =
   everything simply stays pending until a later run finds Google linked — the
   "pending until linked" semantics of the attachment queue without its blocking. No
   prompt anywhere: linking is an explicit Settings/onboarding action; the mirror is a
   background bonus of being linked (same opportunism as the ADR-028/053 Drive
   cascades).
4. **Photo replace/remove invalidates the copy.** The masterlist editor clears
   `driveImageId` whenever the saved `imagePath` differs from the stored one, so the
   next sync mirrors the NEW photo (a fresh Drive file; the old one lingers in Drive
   exactly like ADR-053 delete leftovers — durable copies are never eagerly destroyed).
5. **Bug fix folded in (file-key integrity).** `MasterlistViewModel` minted DIFFERENT
   UUIDs for a new item's photo file and the item row itself, so `{itemId}.webp` never
   matched — photo cleanup on delete missed the file (disk leak) and the mirror could
   never find it. The editor now mints ONE `newItemId` when it opens; the cropper and
   the save path both use it. The `inventory-images` dir/file convention moved to
   `core:data` (`ItemImageFiles.kt`) so writer (`feature:inventory`) and reader
   (`core:google`) share one definition. Photos of items created BEFORE this fix keep
   their mismatched file names and are simply never mirrored from this device — their
   Storage copy is unaffected.

**Consequences.** A linked owner's Drive accumulates every item photo under
`images/inventory/`, restore/backup tooling can rely on `drive_image_id`, and devices
that never link lose nothing. One more push pass per sync run only when something was
actually mirrored. Failure modes are logged under `SamarohDriveMirror`.

## ADR-056 — Defaults: item-photo quality → Space saver, backup frequency → daily (2026-09-07)

**Status:** accepted. Owner-directed defaults change; touches `core:model` /
`core:database` default VALUES only (no schema shape change). Extends ADR-053 (quality
preference) and §4.4 (backups).

**Context.** Item photos render as ≤320px thumbnails — the owner wants the smallest
files out of the box; bills must stay readable. Weekly backups proved too coarse for
active businesses.

**Decision.**
1. **Bill photos default stays High (q90)** — unchanged, now pinned by test.
2. **Item photos default becomes Space saver (q30)** — `ImageQualityPreferences.
   ITEM_DEFAULT` 50 → 30 (the lowest chip; band 30–90 unchanged). Settings renders the
   Space-saver chip selected on a fresh install; an explicit user choice is untouched
   (the keys only default when UNSET).
3. **Backup frequency defaults to daily** everywhere a client default materializes:
   the `BusinessSettings` model + Room entity defaults, `BackupFrequency.fromWire`'s
   unknown-value fallback, and the Settings UI's no-row state. The shared baseline
   schema (`001_schema.sql`) column default changed 'weekly' → 'daily', and
   `scripts/alter-backup-daily.sql` (shared repo) migrates an EXISTING deployment: it
   alters the column default and flips current 'weekly' rows to 'daily' (an explicit
   monthly/manual choice is preserved). The owner runs it once in the Supabase SQL
   editor.

**Consequences.** Fresh installs store item photos at roughly a third of the previous
size and back up daily. No stored user choice is overridden client-side; the alter
script intentionally rewrites only 'weekly' rows (the old default) server-side.

## ADR-057 — Zero-stock items shown at the end of the stock list (2026-09-07)

**Status:** accepted. Feature-level UI semantics change in `feature:inventory`
(deliberate divergence from the web stock screen's `quantity > 0` filter).

**Context.** The Current Inventory screen hid items at zero stock (or with no
transactions yet). Users add an item on the Masterlist, flip to the stock view, and
conclude the item VANISHED — the top confusion report for inventory.

**Decision.** The stock list now shows every live master item: in-stock rows first,
then zero-stock rows, each group alphabetical (the DAO's name ordering + a stable
partition). Zero-stock rows render dimmed (55% alpha) with quantity 0 and ₹0 value so
in-stock rows still dominate visually; search matches them too. The `allZero` empty
state died with the filter (all-zero now just renders an all-dimmed list); the
Masterlist screen is unchanged. The DAO/repository were already returning zero-stock
rows (LEFT JOIN) — the filter lived only in the ViewModel, so this is presentation-only
with no contract change.

**Consequences.** "Where did my item go" resolves itself; the visual weight still
communicates what is actually in stock. Android intentionally diverges from web parity
here until the web track adopts the same ordering.

## ADR-058 — Drive mirror covers storage-only photos; human-readable Drive names (ADR-055 addendum) (2026-09-07)

**Status:** accepted. Owner-directed; additive (new seam in `core:data`, new binding in
`core:auth`, `core:google` implementation changes). Extends ADR-055, whose documented
gap this closes.

**Context.** ADR-055 mirrored an item photo to Drive only when the device-local
`{itemId}.webp` file existed — i.e. only photos taken on THIS device. The owner is about
to re-import ~100 inventory items via the web, whose images land in Supabase Storage
(the serving source) with no local file anywhere: under ADR-055 those rows would never
gain a durable Drive copy. Manually uploading to Drive would leave `drive_image_id`
unset and produce duplicates once mirroring ever ran. Separately, Drive names were
machine-y: item photos as `{name}-{uuid}.webp`, attachments keeping their local cache
name (`bill-{uuid8}.jpg`) — not what a human wants when browsing their own Drive.

**Decision.**
1. **Storage-only rows mirror too.** A pending row (live, `drive_image_id IS NULL`,
   Storage `image_path`) with NO local file now downloads its bytes through a new
   `core:data` seam `ItemPhotoStorageDownloader`, implemented in `core:auth`
   (`StorageItemPhotoDownloader`) over the shared authed Supabase client —
   `downloadAuthenticated` on the private `inventory-images` bucket, RLS as the
   signed-in user, exactly like the ADR-023 display resolver. The bytes pass through a
   cache-dir temp file into the existing `DriveUploader` and the row is stamped
   (Room + outbox) as before. Web imports AND the ADR-055 legacy-photo gap (pre-fix
   mismatched file keys) both become mirrorable from any linked device.
2. **Throttled: at most 10 storage downloads per sync run**
   (`DriveItemImageMirror.MAX_STORAGE_DOWNLOADS_PER_RUN`). A download+upload is two full
   image round-trips per row; syncs fire on every mutation, on foreground and
   periodically, so a 100-item import drains in ~10 unremarkable runs rather than one
   sync doing 200 transfers on a phone connection. Local-file mirrors (this device took
   the photo — one cheap upload, arriving one at a time in practice) stay unthrottled.
   Undelivered rows simply remain pending; the next run continues.
3. **Human-readable Drive names** via a shared `DriveNameFactory` (`core:google`):
   item photos upload as `{item name}-{yyyyMMdd-HHmmss}.webp`, expense attachments as
   `{party name}-{yyyyMMdd-HHmmss}.{ext}` (extension from the stored `file_name`, MIME
   fallback). Bases sanitize for Drive (path separators/control chars → spaces,
   whitespace collapsed, 80-char cap, `item`/`expense` fallback when blank; Devanagari
   passes through untouched). Timestamps render in the DEVICE zone (the owner's wall
   clock) from the injected `Clock`. Names issued within the same second uniquify with a
   `-2`, `-3`… suffix (the set resets each second — bounded memory); Drive tolerates
   duplicate names, but a human browsing the folder should not have to. The Room row's
   `file_name` (app display, local download name) is untouched — the new name is
   Drive-side only.
4. **No rename pass.** Files already mirrored under ADR-055's `{name}-{uuid}` or an
   attachment's cache name keep their names; only NEW uploads use the convention
   (durable copies are never eagerly rewritten, same spirit as ADR-053 leftovers).

**Consequences.** After the web re-import, every item photo lands in the owner's Drive
with a recognizable name within a handful of syncs, `drive_image_id` converges through
the normal outbox (no manual-upload duplicates), and the §9.1 `{item-name}-{item-id}`
naming from ADR-055 is superseded for new uploads. One extra download round-trip per
storage-only mirror, capped per run.

## ADR-059 — Member access to Drive-hosted bills: link-scoped sharing + public-link fallback (2026-09-07)

**Status:** accepted. Additive on frozen contracts (ADR-001 process): Room migration
8→9 (`expense_attachments.drive_permission_ensured`, device-only), additive
`ExpenseAttachmentDao` queries, `DriveService.ensureAnyoneReaderPermission` /
`DriveService.downloadPublicFile`, new `core:data` seam `AttachmentPermissionRepair`.
Extends ADR-052 (attachment resolver) and reuses the ADR-055/058 sync-pass pattern.

**Context.** Bills live ONLY in the uploader's Google Drive (`drive.file` scope — §4.2,
ADR-018). A `drive.file` file is private to the account whose app created it, so
another business MEMBER's token gets 404 on `files.get`: member bill-viewing failed on
Android (the ADR-052 resolver's own-token download) and on the web (the ledger's
`drive.google.com/file/d/{id}/view` chips). Item photos were never affected — Supabase
Storage is their serving source (ADR-023); Drive is only their private durable copy.

**Decision.**
1. **Every bill upload also creates a link-scoped reader permission** —
   `permissions.create` with `role=reader, type=anyone` (file discovery stays off: the
   file is viewable by anyone WITH the link but never searchable). `drive.file` covers
   sharing on app-created files — the same authority the ADR-053 delete cascade already
   exercises. Best-effort inline in `DriveAttachmentUploader`: a permission failure
   never fails the upload; the row's flag stays pending and the repair pass (3) retries.
2. **Resolver fallback ladder** (`AttachmentContentResolver`): local cache → own-token
   `files.get?alt=media` (linked users; their own uploads) → PUBLIC LINK download
   (`https://drive.google.com/uc?export=download&id={id}`, NO credentials — works for
   anyone-with-link files, including signed-out clients) → only then the ADR-052
   "link your Google account to view" prompt (specifically: not-linked + Drive answered
   but withheld the file — an old bill awaiting repair), with network-level failures
   still mapping to the friendly `DownloadFailed` retry state. The `uc?export=download`
   endpoint was chosen over `files.get?key=API_KEY` because the app deliberately ships
   no API key (only the OAuth web client id, spec §6). A 2xx answer whose Content-Type
   is HTML (sign-in page / large-file interstitial — bills are far below that
   threshold) is treated as a failure so an HTML page is never cached as a bill; the
   endpoint is exercised by an integration-shaped test against a local mock HTTP
   server (the ADR-052 download-test pattern).
3. **Retroactive repair on the uploader's device** — pre-ADR-059 bills lack the
   permission. `AttachmentPermissionRepair` (seam in `core:data`, implemented by
   `core:google`'s `DriveAttachmentPermissionRepair`, consumed `Optional` by
   `core:sync` — the exact ADR-055 mirror shape) runs once per sync after the push:
   pending = live row with `drive_file_id` whose DEVICE-ONLY
   `drive_permission_ensured` flag (new column, never synced — exactly like
   `local_cache_path`; preserved across pulls by `LocalApplier`) is unset; at most 10
   rows per run (ADR-058 throttle spirit), oldest first. `permissions.create` is
   idempotent for `type=anyone`, so re-ensuring is harmless. A 403/404 answer means the
   file belongs to ANOTHER member's account: the local flag is set so this device stops
   retrying — the verdict never syncs, so the uploader's own device still repairs the
   file. Not linked stops the pass silently; transient failures stay pending.
4. **Threat model — stated honestly.** Anyone WITH a bill's link can view that bill;
   the mitigations are that Drive file ids are high-entropy and unguessable, discovery
   is off (the permission grants access by id, not by search), and the ids live only in
   `expense_attachments` behind Supabase RLS (business members only) — links never
   leave the app's surfaces. This is the standard "anyone with the link" posture and is
   the deliberate trade for member access without per-member Drive grants (which
   `drive.file` cannot express and which would break on member churn). A leaked link
   exposes exactly one bill image/PDF; revocation = deleting the file (ADR-053 delete
   already does).
5. **Web needs no change** (verified): the ledger chip's
   `drive.google.com/file/d/{id}/view` URL renders anyone-with-link files for any
   browser, signed in or not, once the permission exists.
6. **Item-photo Drive mirrors are deliberately NOT shared.** Members are served from
   Supabase Storage (ADR-023); the ADR-055/058 Drive copies remain the owner's private
   durable backup, so no permission is created on them.

**Consequences.** A member tapping a bill gets the bytes via the public path with no
Google link and no prompt; the link dialog survives only as the honest last resort.
Existing bills become member-visible as the uploader's device syncs (10 per run —
typically within the hour). One extra Drive POST per bill upload. Devices that never
link simply leave their rows pending, as ever. Failure modes are logged under
`SamarohAttach` (inline) and `SamarohDriveRepair` (repair pass).
