# AGENTS.md — samaroh-android

Instructions for AI agents (and humans) working in this repo. Read fully before editing.

## Project overview

Samaroh is an offline-first, multilingual (en + hi) Material You Android app for small
venue businesses: calendar-first **Booking**, party-ledger **Expenses**, photo-per-item
**Inventory**, and a **Menu** tab (Settings/Reports/Members/About). Room is the source of
truth; Supabase is the sync target (never read directly by UI); Google is used for
Sign-In, Drive backups and Calendar sync.

## Module map (module = agent ownership boundary)

| Module | Contents | Status |
|---|---|---|
| `app` | MainActivity, nav host, bottom bar, DI wiring, locale config, offline-banner slot | Wave 0 shell |
| `shared/` | git submodule → `samaroh-shared` (string catalogs, codegen, schema, brand) | external |
| `core:designsystem` | theme (dynamic color + #6750A4 fallback), typography ≥16sp body, `SamarohCard`, `AmountText`, `EmptyState`, `OfflineBanner`, `PermissionGate`, `ExplainableIcon`, `TypeAheadField`, `PlaceholderScreen` | done (W0) |
| `core:i18n` | `generateStrings` codegen task, `LocaleManager`, `AmountFormatter` | done (W0) |
| `core:model` | enums, domain models, permission types — **FROZEN CONTRACT** | done (W0) |
| `core:database` | Room entities/DAOs/converters, `outbox`, exported schemas — **FROZEN CONTRACT** | done (W0) |
| `core:data` | repository interfaces (**FROZEN**) + Room-backed impls, `OutboxWriter`/`SyncScheduler` contracts | done (W0) |
| `core:sync` | `RoomOutboxWriter`, `SyncWorker` skeleton, WorkManager scheduler | shell — full engine is W1-E |
| `core:auth` | `SessionHolder`, `PermissionGuard` interfaces | shell — impl is W1-D |
| `core:google` | empty shell | W1-F |
| `core:invoice` | empty shell | W1-E |
| `core:testing` | `MainDispatcherRule`, `inMemoryDatabase`, `Fixtures` builders | done (W0) |
| `feature:booking/expenses/inventory/menu/onboarding/reports` | `NavGraphBuilder.<x>Graph()` extensions; localized placeholder screens | skeletons |

Feature modules depend ONLY on `core:*`, never on each other. `app` wires the graphs.

## File-ownership map for parallel agents (spec §11)

One agent per module directory; no two concurrent agents edit the same module.

- `W1-A`: `feature/booking` (+ `booking.*` string keys)
- `W1-B`: `feature/expenses` (+ `expenses.*` keys)
- `W1-C`: `feature/inventory` (+ `inventory.*` keys; FIFO calculator impl in `core:data`)
- `W1-D`: `feature/onboarding` + `core/auth` (+ `onboarding.*`, `auth.*` keys)
- `W1-E`: `core/sync` + `core/invoice` (+ `sync.*`, `invoice.*` keys)
- `W1-F`: `core/google` + `feature/menu` (+ `menu.*`, `settings.*` keys)
- `W2-A`: `feature/reports` (+ `reports.*` keys)

The shared string catalog is the one file with per-agent NAMESPACING instead of exclusive
ownership; the integrator resolves (additive JSON) merge conflicts.

## Build / test / lint commands

```bash
git submodule update --init --recursive   # once per checkout

./gradlew generateStrings                 # catalog → generated res (git-ignored)
./gradlew ktlintCheck                     # style (ktlint_official, run ktlintFormat to fix)
./gradlew lintDebug                       # Android lint — HardcodedText/SetTextI18n are ERRORS
./gradlew testDebugUnitTest               # unit tests (Robolectric DAO tests included)
./gradlew assembleDebug                   # app/build/outputs/apk/debug/Samaroh-debug.apk

# the full local quality gate (same as CI):
./gradlew generateStrings ktlintCheck lintDebug testDebugUnitTest assembleDebug

bash scripts/legal-check.sh               # legal-hygiene denylist scan
```

Run ktlint + unit tests before EVERY commit.

## Hard rules

1. **Contract freeze**: changes to `core:model`, `core:database`, repository interfaces in
   `core:data`, the canonical schema, or the string-key namespace require a
   `docs/decisions.md` ADR entry (see ADR-001). New keys within your own feature namespace
   are fine.
2. **i18n — NEVER hardcode a user-visible string.** Not in Kotlin, not in Compose, not in
   placeholders, not in notifications. Add a key to
   `shared/strings/catalog.en.json` + `catalog.hi.json` (both, always — key parity is
   tested), commit in the shared repo, bump the submodule, run `./gradlew generateStrings`,
   and reference the generated resource (`com.itsluminous.samaroh.core.i18n.R.string.*`).
   Android lint `HardcodedText`/`SetTextI18n` are error-severity and CI-blocking.
3. **Legal hygiene — NEVER mention third-party reference products** in code, comments,
   strings, docs or commit messages. Permitted names: Google, Supabase, WhatsApp (as a
   share target), OSS library attributions. `scripts/legal-check.sh` runs in CI (denylist
   is base64-embedded) and fails the build on violations.
4. **Money** is `Long` paise everywhere (ADR-002); render only via
   `AmountFormatter`/`AmountText`. Never `String.format` on money.
5. **Offline-first**: reads from Room only; writes to Room + outbox via repositories.
   UI never blocks on network.
6. Every icon-only control uses `ExplainableIcon` (long-press → localized toast).

## Commit convention

Conventional Commits, imperative mood, subject ≤ 50 chars
(`feat(booking): add month calendar grid`). Commit after every completed task.
**Never force-push. Never push directly to main once branch protection exists** — work on
`feat/<module>` branches; the integrator merges in dependency order.

## Submodule procedure

- `shared/` is a git submodule. It currently points at the LOCAL repo
  `/Users/kupraki/repo/Samaroh/samaroh-shared` (added with
  `git -c protocol.file.allow=always submodule add …`).
  **TODO: re-point the submodule URL to the GitHub remote (`.gitmodules` + `git submodule
  sync`) as soon as the remote repos exist.**
- To consume new shared changes: commit in `samaroh-shared` first, then
  `git -C shared pull origin main` here and commit the submodule pointer bump.
- CI checks out with `submodules: recursive` and verifies codegen is in sync.

## Supabase env setup

`local.properties` (git-ignored) carries `sdk.dir` plus `SUPABASE_URL`,
`SUPABASE_ANON_KEY`, `GOOGLE_WEB_CLIENT_ID` — empty values are safe defaults; the app
builds and runs fully offline without them. Create a Supabase project, `supabase link`,
`supabase db push` from `shared/supabase/migrations/`, then fill in the values.

## Emulator testing (macOS — spec §12)

Android Studio, JDK 17+, SDK 35 and an AVD ALREADY exist on this machine.
**Do NOT create a new AVD or reinstall SDK components.**

```bash
$HOME/Library/Android/sdk/emulator/emulator -list-avds
$HOME/Library/Android/sdk/emulator/emulator -avd <existing-avd-name> &
adb wait-for-device

./gradlew installDebug
adb shell am start -n com.itsluminous.samaroh/.MainActivity

./gradlew connectedDebugAndroidTest   # instrumented tests on the running emulator
```

Test in BOTH languages: switch via system per-app language settings (Android 13+) or the
in-app switcher once W1-F lands.
