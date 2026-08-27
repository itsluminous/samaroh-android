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
| `app` | MainActivity, nav host, bottom bar (business-name app bar + tappable sync cloud icon), DI wiring, locale config, offline banner, e2e suite (`src/androidTest`, en+hi) | done |
| `shared/` | git submodule → `samaroh-shared` (string catalogs, codegen, schema, brand) | external |
| `core:designsystem` | theme (dynamic color + #6750A4 fallback), typography ≥16sp body, `SamarohCard`, `AmountText`, `EmptyState`, `OfflineBanner`, `PermissionGate`, `ExplainableIcon`, `TypeAheadField`, `ChipRow` (scrollable single-line filter pills), `SamarohFab`, `CalendarDayCrossfade`, `cropper/` (interactive square photo crop, ADR-025), motion spec, `PlaceholderScreen` | done |
| `core:i18n` | `generateStrings` codegen task, `LocaleManager`, `AmountFormatter`, catalog parity + usage-audit tests | done |
| `core:model` | enums, domain models, permission types — **FROZEN CONTRACT** | done |
| `core:database` | Room entities/DAOs/converters, `outbox`, sync cursors/conflict log, exported schemas — **FROZEN CONTRACT** | done |
| `core:data` | repository interfaces (**FROZEN**, additive extensions via ADRs) + Room-backed impls, `OutboxWriter`/`SyncScheduler`/`SyncStatusProvider` contracts, settings DataStore, session providers | done |
| `core:sync` | full sync engine (keyset pull, LWW, post-sync hooks), `SyncWorker`, `SyncRunState` (drives `SyncStatus.isSyncing`, ADR-029), human-readable sync entries (ADR-022), WorkManager scheduler | done |
| `core:auth` | Supabase auth (`SessionHolder`), `PermissionGuard` | done |
| `core:google` | Google linking, Drive backups/attachments, Calendar sync | done |
| `core:invoice` | PDF renderer + invoice numbering (layout per `shared/invoice/layout-spec.md`) | done |
| `core:testing` | `MainDispatcherRule`, `inMemoryDatabase`, `Fixtures` builders | done |
| `feature:booking` | month calendar (crossfade cells), **events-view agenda** (windowed loading, ADR-029), booking card sheet, add/edit form, reminders/follow-ups, invoicing | done |
| `feature:expenses` | party ledger, business/personal party flag (ADR-027), party edit/cascade delete (ADR-028), attachments, `ExpensesSession` perm gate | done |
| `feature:inventory` | stock + masterlist, FIFO (ADR-012), item detail w/ txn history, `ui/MasterItemDialogs.kt` (shared add/edit/delete dialogs), `InventorySession` perm gate | done |
| `feature:menu` | settings (language/theme/reminders/icon-crossfade slider/form fields), sync-status screen (`SyncEntryDisplay`), members, business profile, about (version, source link, UPI donate) | done |
| `feature:onboarding` | sign-in/sign-up, create/join business, Google link, offline continue | done |
| `feature:reports` | nine reports, Compose charts, totals rows, personal-expenses report, CSV/PDF export | done |

Feature modules depend ONLY on `core:*`, never on each other. `app` wires the graphs.

## File-ownership map for parallel agents (spec §11 — historical; all waves shipped)

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

- `shared/` is a git submodule pointing at the GitHub remote
  (`https://github.com/itsluminous/samaroh-shared.git`).
- To consume new shared changes: commit **and push** in `samaroh-shared` first
  (`git pull --ff-only` there before pushing — the web track bumps it too), then
  `git -C shared pull origin main` here and commit the submodule pointer bump
  (`chore(shared): bump …`). Never point the submodule at a local path or an
  unpushed commit — CI and other clones can't resolve it.
- CI checks out with `submodules: recursive` and verifies codegen is in sync.

## Release process

- Releases are cut by tagging `main` with `vX.Y.Z` and pushing the tag. The Release
  workflow (`.github/workflows/release.yml`) re-runs the full CI gate, builds a signed
  release APK and creates the GitHub Release. **Keystore and Supabase signing/config
  secrets are already configured** in the repo (forks fall back to a debug-signed APK).
- **Release notes are REQUIRED on the GitHub Release**: write a curated summary of the
  changes since the last tag (user-facing wording, grouped by area) — never a raw
  `git log` dump.
- The About screen shows the app version from the package's `versionName`
  (`BuildConfig.VERSION_NAME`), which derives from the tag at release build time
  (`v0.1.0` → `-PappVersionName=0.1.0`; local/debug builds show the default set in `app/build.gradle.kts`).
  **Verify Menu → About reflects the new version as part of every release.**

## Working conventions (learned)

- **Fragments per namespace**: new string keys go in the shared repo's
  `strings/fragments/<namespace>.{en,hi}.json` for your feature — never another
  feature's fragment or the base catalog. Keeps parallel tracks merge-conflict-free.
- **ADR process**: any contract-adjacent change (additive repository method, new
  `core:database` query, sync semantics, cross-feature component) gets a numbered entry
  in `docs/decisions.md` (ADR-001…029 so far) in the same change. Additive-only; state
  what and why.
- **Anti-stall rule for emulator evidence**: every adb/emulator command in an agent run
  must be wrapped in a timeout, and UI verification is done via screenshots
  (`adb exec-out screencap`) captured non-interactively — never block on an interactive
  command or an emulator window.
- **Phone read-only rule**: if a physical phone is attached over adb, it is READ-ONLY —
  never install, uninstall, clear data, or push files to it. All install/verify work
  happens on the emulator (see below).
- **Shared-push coordination**: pushes to `samaroh-shared` come from multiple tracks;
  always `git pull --ff-only` in the shared repo before pushing, and bump the submodule
  here only to commits that exist on the shared remote.

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

## End-to-end suite & catalog audit (W2-B)

- `app/src/androidTest` hosts the e2e suite: Compose UI tests with `HiltAndroidRule`
  and the custom `SamarohTestRunner`. Every acceptance flow runs TWICE via `En`/`Hi`
  subclass pairs of `LocalizedE2eTest` — assertions resolve expected strings from the
  app's own resources under the run's locale, so tests catch localization regressions
  without hardcoding either language.
- Tests are hermetic: `TestSyncModule` pins the sync remote to "unconfigured" (nothing
  is pushed to a live backend even when `local.properties` has real credentials), and
  the DataStore test modules keep one process-wide instance per preferences file
  (Hilt builds a fresh component per test class, and DataStore crashes if two
  instances open the same file).
- `core:i18n` unit tests audit the catalog: `CatalogKeyParityTest` walks the base
  catalog PLUS every `fragments/*.json`, and `CatalogUsageAuditTest` FAILS the build
  when Kotlin code references a string resource with no catalog key; unused keys are
  only warned (they may be web-only). Keys resolved dynamically (no compile-time
  `R.string` token) must be added to that test's allowlist to keep the unused-key
  report meaningful.
