package com.itsluminous.samaroh.feature.menu.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
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
    /** Booking-form optional fields (ADR-020): deposit hidden by default. */
    val bookingFormShowDeposit: Boolean,
    val bookingFormShowSource: Boolean,
    val bookingFormShowTimes: Boolean,
    /** Watermark opacity of day-cell event icons on the booking calendar (0.15–0.9). */
    val bookingCalendarIconAlpha: Float,
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
                    bookingFormShowDeposit = prefs[KEY_BOOKING_FORM_SHOW_DEPOSIT] ?: false,
                    bookingFormShowSource = prefs[KEY_BOOKING_FORM_SHOW_SOURCE] ?: true,
                    bookingFormShowTimes = prefs[KEY_BOOKING_FORM_SHOW_TIMES] ?: true,
                    bookingCalendarIconAlpha =
                        (prefs[KEY_BOOKING_CALENDAR_ICON_ALPHA] ?: DEFAULT_CALENDAR_ICON_ALPHA)
                            .coerceIn(CALENDAR_ICON_ALPHA_MIN, CALENDAR_ICON_ALPHA_MAX),
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

        suspend fun setBookingFormShowDeposit(show: Boolean) {
            dataStore.edit { it[KEY_BOOKING_FORM_SHOW_DEPOSIT] = show }
        }

        suspend fun setBookingFormShowSource(show: Boolean) {
            dataStore.edit { it[KEY_BOOKING_FORM_SHOW_SOURCE] = show }
        }

        suspend fun setBookingFormShowTimes(show: Boolean) {
            dataStore.edit { it[KEY_BOOKING_FORM_SHOW_TIMES] = show }
        }

        suspend fun setBookingCalendarIconAlpha(alpha: Float) {
            dataStore.edit {
                it[KEY_BOOKING_CALENDAR_ICON_ALPHA] = alpha.coerceIn(CALENDAR_ICON_ALPHA_MIN, CALENDAR_ICON_ALPHA_MAX)
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

            // Booking-form field visibility (ADR-020) — read by feature:booking's form.
            val KEY_BOOKING_FORM_SHOW_DEPOSIT = booleanPreferencesKey("booking_form_show_security_deposit")
            val KEY_BOOKING_FORM_SHOW_SOURCE = booleanPreferencesKey("booking_form_show_source")
            val KEY_BOOKING_FORM_SHOW_TIMES = booleanPreferencesKey("booking_form_show_times")

            // Calendar-icon watermark opacity — read by feature:booking's day cells.
            val KEY_BOOKING_CALENDAR_ICON_ALPHA = floatPreferencesKey("booking_calendar_icon_alpha")

            val DEFAULT_LEAD_DAYS: Set<Int> = sortedSetOf(1, 3)

            /** Default matches the original hardcoded watermark opacity. */
            const val DEFAULT_CALENDAR_ICON_ALPHA = 0.45f

            /** Slider bounds: never invisible, never so strong the date drowns. */
            const val CALENDAR_ICON_ALPHA_MIN = 0.15f
            const val CALENDAR_ICON_ALPHA_MAX = 0.9f
        }
    }
