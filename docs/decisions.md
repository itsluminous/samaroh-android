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

## ADR-007 — FIFO inventory calculator and overview queries (W1-C) (2026-08-25)

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
