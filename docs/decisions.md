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
