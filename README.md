# Samaroh — Android

Samaroh (समारोह) is an offline-first business management app for small venue businesses —
marriage halls, banquet halls, community halls, guest houses. One beautiful, multilingual
app for bookings, expenses and inventory.

> Wave 0 status: project foundation. Placeholder screens, frozen data contracts, CI, and
> the shared string-catalog pipeline are in place; features land in Wave 1.

## Features (v1 scope)

- 📅 **Booking** — calendar-first booking management: status-colored month view,
  one-card booking details, payments & dues, reminders, invoices, WhatsApp sharing.
- 💰 **Expenses** — clutter-free party ledger: "You gave / You got", running balances,
  invoice attachments stored in Google Drive.
- 📦 **Inventory** — photo-per-item stock with simple add/remove transactions and FIFO
  valuation.
- ☰ **Menu** — settings (language, theme, reminders, backup), reports, members (RBAC),
  about.
- 🌐 **English + हिन्दी** everywhere, switchable in-app; built to add more languages as
  translation-only work.
- ✈️ **Offline-first** — everything works without network; changes sync when connectivity
  returns.

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
Tagging `v*` builds a signed release APK and attaches it to a GitHub Release.

## Documentation

- [AGENTS.md](AGENTS.md) — contributor/agent handbook
- [docs/decisions.md](docs/decisions.md) — architecture decision records
- [docs/backlog.md](docs/backlog.md) — v2+ feature backlog
- [docs/backup-format.md](docs/backup-format.md) — backup ZIP format (WIP)
