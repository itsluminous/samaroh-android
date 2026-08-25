package com.itsluminous.samaroh.feature.menu.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.itsluminous.samaroh.feature.menu.di.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Theme selection (§4.4): System / Light / Dark. */
enum class ThemeMode(
    val wire: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromWire(value: String?): ThemeMode = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

/** Upcoming-booking reminder style (§4.1/§4.4). Wire values are the DataStore contract. */
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

/** Device-local settings snapshot (§4.4 "Stored per device in DataStore"). */
data class DeviceSettings(
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    /** Days before an event to remind, e.g. {1, 3, 7} — sorted ascending. */
    val reminderLeadDays: Set<Int>,
    val reminderStyle: ReminderStyle,
    /** Ringtone URI for the full-screen popup, or null for the system default. */
    val reminderSoundUri: String?,
)

/**
 * The device-settings DataStore (preferences file `"settings"`).
 *
 * CONTRACT (shared with `feature:booking`'s reminder engine — keys are frozen):
 * - `booking_reminder_lead_days`: Set<String> of day counts
 * - `booking_reminder_style`: String — `notification` | `fullscreen`
 * - `booking_reminder_sound_uri`: String — ringtone URI
 *
 * Theme keys (`theme_mode`, `dynamic_color`) are menu-owned.
 */
@Singleton
class SettingsPreferencesDataSource
    @Inject
    constructor(
        @SettingsDataStore private val dataStore: DataStore<Preferences>,
    ) {
        val settings: Flow<DeviceSettings> =
            dataStore.data.map { prefs ->
                DeviceSettings(
                    themeMode = ThemeMode.fromWire(prefs[KEY_THEME_MODE]),
                    dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
                    reminderLeadDays =
                        prefs[KEY_REMINDER_LEAD_DAYS]
                            ?.mapNotNull(String::toIntOrNull)
                            ?.toSortedSet()
                            ?: DEFAULT_LEAD_DAYS,
                    reminderStyle = ReminderStyle.fromWire(prefs[KEY_REMINDER_STYLE]),
                    reminderSoundUri = prefs[KEY_REMINDER_SOUND_URI]?.ifEmpty { null },
                )
            }

        suspend fun setThemeMode(mode: ThemeMode) {
            dataStore.edit { it[KEY_THEME_MODE] = mode.wire }
        }

        suspend fun setDynamicColor(enabled: Boolean) {
            dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
        }

        suspend fun setReminderLeadDays(days: Set<Int>) {
            dataStore.edit { it[KEY_REMINDER_LEAD_DAYS] = days.map(Int::toString).toSet() }
        }

        suspend fun setReminderStyle(style: ReminderStyle) {
            dataStore.edit { it[KEY_REMINDER_STYLE] = style.wire }
        }

        suspend fun setReminderSoundUri(uri: String?) {
            dataStore.edit { prefs ->
                if (uri.isNullOrEmpty()) prefs.remove(KEY_REMINDER_SOUND_URI) else prefs[KEY_REMINDER_SOUND_URI] = uri
            }
        }

        companion object {
            /** DataStore preferences file name — the cross-feature contract. */
            const val FILE_NAME = "settings"

            val KEY_REMINDER_LEAD_DAYS = stringSetPreferencesKey("booking_reminder_lead_days")
            val KEY_REMINDER_STYLE = stringPreferencesKey("booking_reminder_style")
            val KEY_REMINDER_SOUND_URI = stringPreferencesKey("booking_reminder_sound_uri")
            val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
            val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")

            val DEFAULT_LEAD_DAYS: Set<Int> = sortedSetOf(1, 3)
        }
    }
