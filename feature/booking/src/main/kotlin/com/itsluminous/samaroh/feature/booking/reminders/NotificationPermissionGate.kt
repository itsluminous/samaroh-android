package com.itsluminous.samaroh.feature.booking.reminders

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether opening the add/edit booking form should fire the contextual
 * POST_NOTIFICATIONS request (ADR-043 plumbing, moved to form-open by ADR-044): the
 * form is where reminders are born, so it is the earliest moment the permission
 * matters — and asking on OPEN beats asking after save (the user still has context,
 * and a denial doesn't interrupt the exit).
 *
 * Pure and unit-tested. The three inputs disambiguate Android's tri-state rationale:
 * - never asked yet → `requestedBefore=false`, rationale false → PROMPT;
 * - denied once → rationale true → PROMPT (the system dialog can still show);
 * - permanently denied ("don't ask again" / repeated denials) → `requestedBefore=true`
 *   AND rationale false → NO prompt: launching would only flash an auto-denial. The
 *   Settings → Booking reminders status rows (ADR-043 §2) remain the fix path.
 *
 * `notificationsEnabled` comes from `areNotificationsEnabled()`, which is also false
 * for an app-level block with the permission granted — the prompt is a no-op then,
 * which the permanently-denied guard equally suppresses after the first attempt.
 */
object NotificationPermissionGate {
    fun shouldPrompt(
        sdkInt: Int,
        notificationsEnabled: Boolean,
        requestedBefore: Boolean,
        shouldShowRationale: Boolean,
    ): Boolean = sdkInt >= 33 && !notificationsEnabled && (!requestedBefore || shouldShowRationale)
}

/**
 * Per-device "we already fired the POST_NOTIFICATIONS dialog once" flag backing the
 * gate's `requestedBefore` input, on the SHARED settings DataStore (single Hilt
 * instance from `core:data`). Interface so ViewModel tests fake it without DataStore.
 */
interface NotificationPromptPrefs {
    val requestedBefore: Flow<Boolean>

    suspend fun markRequested()
}

@Singleton
class DataStoreNotificationPromptPrefs
    @Inject
    constructor(
        @SettingsDataStore private val dataStore: DataStore<Preferences>,
    ) : NotificationPromptPrefs {
        override val requestedBefore: Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[KEY_REQUESTED] ?: false }

        override suspend fun markRequested() {
            dataStore.edit { prefs -> prefs[KEY_REQUESTED] = true }
        }

        companion object {
            val KEY_REQUESTED = booleanPreferencesKey("booking_notification_permission_requested")
        }
    }

/** One-shot read for the form-open check. */
suspend fun NotificationPromptPrefs.requestedBeforeOnce(): Boolean = requestedBefore.first()
