package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
import com.itsluminous.samaroh.core.designsystem.component.CalendarDayCrossfade
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Read side of the booking-calendar appearance preferences. */
interface BookingCalendarPrefs {
    /**
     * Date ↔ icon crossfade slider value, always within 0.15–0.9. Drives BOTH the
     * event-icon watermark opacity and the booked-cell date-number fade via
     * [CalendarDayCrossfade].
     */
    val iconWatermarkAlpha: Flow<Float>

    /** Whether the Booking tab shows the full agenda (events) list instead of the month grid. */
    val eventsView: Flow<Boolean>

    /** Persists the events-view toggle (per device — DataStore, never synced). */
    suspend fun setEventsView(enabled: Boolean)
}

/**
 * DataStore-backed implementation on the SHARED settings file (single Hilt instance from
 * `core:data`), following the ADR-020 pattern of [com.itsluminous.samaroh.feature.booking.ui.form.DataStoreBookingFormFieldPrefs].
 * The Settings screen (`feature:menu`, "Booking calendar") writes the same key.
 */
@Singleton
class DataStoreBookingCalendarPrefs
    @Inject
    constructor(
        @SettingsDataStore private val dataStore: DataStore<Preferences>,
    ) : BookingCalendarPrefs {
        override val iconWatermarkAlpha: Flow<Float> =
            dataStore.data.map { prefs ->
                (prefs[KEY_ICON_WATERMARK_ALPHA] ?: DEFAULT_ICON_WATERMARK_ALPHA)
                    .coerceIn(ICON_WATERMARK_ALPHA_MIN, ICON_WATERMARK_ALPHA_MAX)
            }

        override val eventsView: Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[KEY_EVENTS_VIEW] ?: false }

        override suspend fun setEventsView(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[KEY_EVENTS_VIEW] = enabled }
        }

        companion object {
            val KEY_ICON_WATERMARK_ALPHA = floatPreferencesKey("booking_calendar_icon_alpha")

            /** Month grid ⇄ full agenda list toggle (§4.1 events view) — per device. */
            val KEY_EVENTS_VIEW = booleanPreferencesKey("booking_calendar_events_view")

            /**
             * Default slider value (matches the original hardcoded watermark opacity):
             * below the crossfade's date-fade start, so out of the box the date number
             * stays fully opaque over a legible watermark.
             */
            const val DEFAULT_ICON_WATERMARK_ALPHA = CalendarDayCrossfade.SLIDER_DEFAULT

            /** Bounds mirrored by the Settings slider — see [CalendarDayCrossfade]. */
            const val ICON_WATERMARK_ALPHA_MIN = CalendarDayCrossfade.SLIDER_MIN
            const val ICON_WATERMARK_ALPHA_MAX = CalendarDayCrossfade.SLIDER_MAX
        }
    }
