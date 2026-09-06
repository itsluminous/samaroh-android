package com.itsluminous.samaroh.core.google

/**
 * Central access point for the Google OAuth web client id
 * ([BuildConfig.GOOGLE_WEB_CLIENT_ID], sourced from `local.properties`).
 *
 * When the id is blank the whole Google integration degrades gracefully into a localized
 * "not configured" state (spec §6: the app is fully usable without any secrets) — no
 * Google UI is launched and workers no-op. See docs/google-setup.md for Cloud console
 * setup steps.
 */
object GoogleServicesConfig {
    val webClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    val isConfigured: Boolean = webClientId.isNotBlank()

    /** OAuth scope for Drive access limited to files the app itself created (§9.1). */
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    /** OAuth scope for managing calendar events (one-way booking push, §4.1). */
    const val SCOPE_CALENDAR_EVENTS = "https://www.googleapis.com/auth/calendar.events"

    /**
     * OAuth scope that can CREATE secondary calendars and fully manage events on the
     * calendars the app itself created (ADR-046) — the least-privilege way to give the
     * bookings a dedicated "Samaroh" calendar instead of the primary one (ADR-015's
     * blocker was that `calendar.events` cannot create calendars). `calendar.events`
     * stays requested alongside it: it is still needed to migrate/clean up the events
     * previously pushed to the primary calendar.
     */
    const val SCOPE_CALENDAR_APP_CREATED = "https://www.googleapis.com/auth/calendar.app.created"

    /** Incremental scopes requested at link time. */
    val requestedScopes: List<String> = listOf(SCOPE_DRIVE_FILE, SCOPE_CALENDAR_EVENTS, SCOPE_CALENDAR_APP_CREATED)

    /**
     * The pre-ADR-046 scope set. Existing grants only cover these; the silent token
     * fetch falls back to them so Drive/Calendar keep working until the user re-links
     * and consents to [SCOPE_CALENDAR_APP_CREATED].
     */
    val legacyScopes: List<String> = listOf(SCOPE_DRIVE_FILE, SCOPE_CALENDAR_EVENTS)
}
