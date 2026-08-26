package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Read side of the booking-calendar appearance preferences. */
interface BookingCalendarPrefs {
    /** Watermark opacity of day-cell event icons, always within 0.15–0.9. */
    val iconWatermarkAlpha: Flow<Float>
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

        companion object {
            val KEY_ICON_WATERMARK_ALPHA = floatPreferencesKey("booking_calendar_icon_alpha")

            /**
             * Default watermark opacity (matches the original hardcoded value): low
             * enough that the full-opacity date number on top stays clearly legible,
             * high enough that the icon reads at a glance.
             */
            const val DEFAULT_ICON_WATERMARK_ALPHA = 0.45f

            /** Bounds mirrored by the Settings slider: never invisible, never drowning the date. */
            const val ICON_WATERMARK_ALPHA_MIN = 0.15f
            const val ICON_WATERMARK_ALPHA_MAX = 0.9f
        }
    }
