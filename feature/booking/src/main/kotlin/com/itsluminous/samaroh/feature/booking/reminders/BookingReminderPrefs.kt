package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-device upcoming-reminder preferences (§4.1/§4.4), stored in the DataStore file
 * "settings" under the agreed keys so the Settings screen (W1-F) reads/writes the SAME
 * values:
 * - `booking_reminder_lead_days`: Set<String> of day counts (e.g. "1","3","7");
 * - `booking_reminder_style`: "notification" | "fullscreen";
 * - `booking_reminder_sound_uri`: ringtone uri for the full-screen style.
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ReminderStyle(
    val wire: String,
) {
    NOTIFICATION("notification"),
    FULLSCREEN("fullscreen"),
    ;

    companion object {
        fun fromWire(value: String?): ReminderStyle = entries.firstOrNull { it.wire == value } ?: NOTIFICATION
    }
}

data class UpcomingReminderPrefs(
    val leadDays: Set<Int>,
    val style: ReminderStyle,
    val soundUri: String?,
) {
    companion object {
        /** Default: remind 1 day before, as a simple notification (§4.1). */
        val DEFAULT = UpcomingReminderPrefs(leadDays = setOf(1), style = ReminderStyle.NOTIFICATION, soundUri = null)
    }
}

@Singleton
class BookingReminderPrefs
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val leadDaysKey = stringSetPreferencesKey("booking_reminder_lead_days")
        private val styleKey = stringPreferencesKey("booking_reminder_style")
        private val soundUriKey = stringPreferencesKey("booking_reminder_sound_uri")

        val prefs: Flow<UpcomingReminderPrefs> =
            context.settingsDataStore.data.map { store ->
                UpcomingReminderPrefs(
                    leadDays =
                        store[leadDaysKey]
                            ?.mapNotNull { it.toIntOrNull() }
                            ?.toSet()
                            ?: UpcomingReminderPrefs.DEFAULT.leadDays,
                    style = ReminderStyle.fromWire(store[styleKey]),
                    soundUri = store[soundUriKey]?.ifBlank { null },
                )
            }

        suspend fun current(): UpcomingReminderPrefs = prefs.first()
    }
