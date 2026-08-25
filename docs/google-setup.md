# Google Setup (Sign-In, Drive, Calendar)

Samaroh uses Google for account linking, Drive backups/attachments (`drive.file` scope)
and one-way Calendar sync (`calendar.events` scope). All of it is driven by one value:

```
GOOGLE_WEB_CLIENT_ID   (local.properties, git-ignored → BuildConfig)
```

**When the value is empty the app still builds and runs fully offline** — Settings shows
a localized "Google features are not set up in this build" state, the link button is
hidden, and the backup/calendar workers no-op. Nothing crashes. This is the supported
state for CI and fresh checkouts (spec §6 security).

## 1. Create / pick a Google Cloud project

1. Open <https://console.cloud.google.com/> and create a project (e.g. `samaroh`).
2. **APIs & Services → Library**: enable
   - **Google Drive API**
   - **Google Calendar API**

## 2. Configure the OAuth consent screen

1. **APIs & Services → OAuth consent screen** → User type **External** → Create.
2. App name `Samaroh`, support email, developer contact → Save.
3. **Scopes → Add or remove scopes**, add:
   - `https://www.googleapis.com/auth/drive.file` (per-file access to app-created files)
   - `https://www.googleapis.com/auth/calendar.events`
4. While the app is in *Testing* publishing status, add your Google account under
   **Test users** (only test users can complete the consent flow).

## 3. Get the debug keystore SHA-1

Credential Manager validates the calling app by package name + signing certificate, so an
**Android** client for the debug keystore must exist in the project. Print the SHA-1:

```bash
keytool -list -v \
  -keystore ~/.android/debug.keystore \
  -alias androiddebugkey \
  -storepass android -keypass android | grep 'SHA1:'
```

(The debug keystore is created automatically by the first Android build. For release
builds repeat this with the release keystore and add a second Android client.)

## 4. Create the OAuth clients

**APIs & Services → Credentials → Create credentials → OAuth client ID**, twice:

1. **Android** client:
   - Package name: `com.itsluminous.samaroh`
   - SHA-1: the fingerprint from step 3.
   - (No client id needs to be copied from this one — its existence is what matters.)
2. **Web application** client:
   - No redirect URIs needed for this flow.
   - Copy its **Client ID** — the value ending in `.apps.googleusercontent.com`.
     This "server client id" is what `GetGoogleIdOption` requires on Android.

## 5. Wire it into the build

Append to `local.properties` (never commit this file):

```properties
GOOGLE_WEB_CLIENT_ID=1234567890-abcdefg.apps.googleusercontent.com
```

Rebuild. Settings → Google account now shows **Link Google account**; linking runs the
Credential Manager account picker followed by the incremental consent for the
`drive.file` + `calendar.events` scopes.

## Troubleshooting

- `GetCredentialException` / developer console error → the Android client's package
  name or SHA-1 does not match the APK. Recheck step 3–4 (emulator/AS installs sign with
  the debug keystore of the machine that built the APK).
- Consent screen loops or `access_denied` → your account is not in **Test users**.
- Token fetch returns null after linking → the grant was revoked at
  <https://myaccount.google.com/permissions>; relink from Settings.
