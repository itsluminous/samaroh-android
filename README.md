# Samaroh — Android

[![CI](https://github.com/itsluminous/samaroh-android/actions/workflows/ci.yml/badge.svg)](https://github.com/itsluminous/samaroh-android/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/itsluminous/samaroh-android?sort=semver)](https://github.com/itsluminous/samaroh-android/releases/latest)

Samaroh (समारोह) is an offline-first business management app for small venue businesses —
marriage halls, banquet halls, community halls, guest houses. One beautiful, multilingual
app for bookings, expenses and inventory.

## Feature tour

### 📅 Booking
- **Month calendar** with status-colored cells: the date number sits atop a translucent
  event-type icon, with a crossfade between icon and date (opacity adjustable in
  Settings). Fully accessible — cells carry localized content descriptions.
- **Events view**: switch the calendar to a scrolling agenda of all bookings (three-dots
  menu → "Events view"). Grouped by date, anchored at today, loads older/newer bookings
  on demand as you scroll; the choice persists per device.
- **Booking card** — one sheet for everything: details, payments & running dues,
  reminders, follow-ups, invoice actions.
- **Payments, reminders & follow-ups**: record advance/partial payments, get scheduled
  payment reminders (style configurable in Settings), and log follow-up notes per
  booking.
- **Invoices + WhatsApp**: generate a PDF invoice (numbered, bilingual layout from the
  shared contract) and share it — WhatsApp is a first-class share target.
- Date blocks for holidays/maintenance; conflict warnings when double-booking.

### 💰 Expenses
- Clutter-free **party ledger**: "You gave / You got" entries with running balances.
- **Business/personal flag per party** ("Associated with {business}?" — default yes).
  Personal parties are tagged on rows, excluded from business reports, and get their own
  Personal-expenses report.
- Party edit & delete (cascade tombstone of the party's entries), invoice **attachments**
  per entry (stored via Google Drive), scrollable filter pills.

### 📦 Inventory
- Photo-per-item stock with a masterlist and simple add/remove transactions; zero-stock
  rows hidden from the stock list.
- **FIFO valuation** — recorded transactions surface their FIFO cost; inventory
  purchases count as spend in the money reports.
- **Item detail** screen per item: photo, availability, full transaction history, and
  edit/delete of the master item (delete is blocked while transactions exist).
- Add/edit dialog with fuzzy duplicate-name chips (similarity %), unit picker, and an
  **interactive square photo crop** on upload.

### 📊 Reports
- Nine reports with hand-rolled Compose charts: revenue, profit (cash-basis), expense
  summary, dues, occupancy, and more — date-range filtered.
- **Personal expenses** report (monthly + by-party) from the personal-party flag.
- Every tabular money report ends in a **TOTAL row**, on screen and in exports.
- **CSV export** in machine-readable form (plain decimal rupees, unambiguous dates) plus
  PDF export via the share sheet.

### ☰ Menu
- **Settings**: in-app **language** switcher (English/हिन्दी), **theme**
  (light/dark/system + dynamic color), **reminder style**, calendar **icon-crossfade
  opacity slider**, and **booking-form field** preferences (choose which optional fields
  the form shows).
- **Sync status**: human-readable list of what synced and when, tappable cloud icon in
  the app bar (spins while a sync is running), "Sync now".
- Members with role-based permissions, business profile, backups (Google Drive),
  Google Calendar sync, About (version, source link, donate via UPI).

### ✈️ Offline-first & sync
- Room is the source of truth; every screen works with no network. Writes queue in an
  outbox and sync to Supabase when connectivity returns (keyset pull, last-write-wins).
- Signed-out use is fully supported: the app runs in owner mode on this device without
  an account — sync, members and Google features simply stay dormant until you sign in.

### 🌐 Languages
English + हिन्दी everywhere — every string, chart label, notification and PDF — switchable
in-app; built to add more languages as translation-only work.

## Screenshots

<!-- TODO: add screenshots (calendar month + events view, booking card, party ledger,
item detail, reports, settings) in en + hi. -->

## Tech

Kotlin 2.x · Jetpack Compose (Material 3, dynamic color) · Hilt · Room · WorkManager ·
kotlinx-serialization · Supabase (Postgres/Auth/Storage) · Google Drive & Calendar.

## Repo layout

Multi-module Gradle project; see [AGENTS.md](AGENTS.md) for the module map and rules.
Cross-platform contracts (string catalogs, schema, brand, invoice layout) live in the
[`shared/`](shared/) submodule.

## Build

```bash
git clone <this-repo> && cd samaroh-android
git submodule update --init --recursive
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # + Supabase/Google values, see AGENTS.md

./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/Samaroh-debug.apk
```

## Test & quality gate

```bash
./gradlew generateStrings ktlintCheck lintDebug testDebugUnitTest assembleDebug
bash scripts/legal-check.sh
```

CI runs the same gate on every push/PR and uploads an installable debug APK artifact.
Instrumented e2e tests (`app/src/androidTest`, en + hi) run on an emulator via
`./gradlew connectedDebugAndroidTest`.

## Release process

1. Tag `main` with `vX.Y.Z` and push the tag. The Release workflow
   (`.github/workflows/release.yml`) re-runs the full CI gate, builds a **signed release
   APK** (keystore + Supabase secrets are configured in the repo; forks fall back to a
   debug-signed APK) and attaches it to a GitHub Release.
2. `versionName` derives from the tag (`v0.1.0` → `0.1.0`) via `-PappVersionName`;
   `versionCode` from the workflow run number. The About screen displays this version.
3. **Release notes are required** on the GitHub Release: a curated summary of changes
   since the last tag (not a raw commit dump).
4. After installing the release build, verify **Menu → About** shows the new version.

## Documentation

- [AGENTS.md](AGENTS.md) — contributor/agent handbook
- [docs/decisions.md](docs/decisions.md) — architecture decision records (ADR-001…029)
- [docs/backlog.md](docs/backlog.md) — v2+ feature backlog
- [docs/backup-format.md](docs/backup-format.md) — backup ZIP format
