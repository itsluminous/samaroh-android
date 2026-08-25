package com.itsluminous.samaroh.feature.booking.ui.form

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which optional booking-form fields are visible (ADR-020). Security deposit is HIDDEN
 * by default — most owners never take one; the rest default to visible.
 */
data class BookingFormFieldVisibility(
    val showSecurityDeposit: Boolean = false,
    val showSource: Boolean = true,
    val showTimes: Boolean = true,
)

/** Read side of the booking-form field-visibility preferences (ADR-020). */
interface BookingFormFieldPrefs {
    val visibility: Flow<BookingFormFieldVisibility>
}

/**
 * DataStore-backed implementation on the SHARED settings file (single Hilt instance from
 * `core:data`) under the ADR-020 keys. The Settings screen (`feature:menu`,
 * "Booking form fields") writes the same keys.
 */
@Singleton
class DataStoreBookingFormFieldPrefs
    @Inject
    constructor(
        @SettingsDataStore private val dataStore: DataStore<Preferences>,
    ) : BookingFormFieldPrefs {
        override val visibility: Flow<BookingFormFieldVisibility> =
            dataStore.data.map { prefs ->
                BookingFormFieldVisibility(
                    showSecurityDeposit = prefs[KEY_SHOW_SECURITY_DEPOSIT] ?: false,
                    showSource = prefs[KEY_SHOW_SOURCE] ?: true,
                    showTimes = prefs[KEY_SHOW_TIMES] ?: true,
                )
            }

        companion object {
            val KEY_SHOW_SECURITY_DEPOSIT = booleanPreferencesKey("booking_form_show_security_deposit")
            val KEY_SHOW_SOURCE = booleanPreferencesKey("booking_form_show_source")
            val KEY_SHOW_TIMES = booleanPreferencesKey("booking_form_show_times")
        }
    }
