package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The calendar-prefs read side consumes the EXACT key the Settings screen writes
 * (`booking_calendar_icon_alpha` in file "settings") — the cross-feature contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreBookingCalendarPrefsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val storeScope = CoroutineScope(dispatcher + Job())

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tmp.root, "settings.preferences_pb")
        }
    }
    private val prefs by lazy { DataStoreBookingCalendarPrefs(dataStore) }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `defaults to the original watermark opacity before any write`() =
        testScope.runTest {
            assertThat(prefs.iconWatermarkAlpha.first()).isEqualTo(0.45f)
        }

    @Test
    fun `reflects the value written under the shared contract key`() =
        testScope.runTest {
            dataStore.edit { it[floatPreferencesKey("booking_calendar_icon_alpha")] = 0.8f }
            assertThat(prefs.iconWatermarkAlpha.first()).isEqualTo(0.8f)

            dataStore.edit { it[floatPreferencesKey("booking_calendar_icon_alpha")] = 0.2f }
            assertThat(prefs.iconWatermarkAlpha.first()).isEqualTo(0.2f)
        }

    @Test
    fun `out-of-range stored values are clamped to the slider bounds`() =
        testScope.runTest {
            dataStore.edit { it[floatPreferencesKey("booking_calendar_icon_alpha")] = 1.5f }
            assertThat(prefs.iconWatermarkAlpha.first()).isEqualTo(0.9f)

            dataStore.edit { it[floatPreferencesKey("booking_calendar_icon_alpha")] = 0f }
            assertThat(prefs.iconWatermarkAlpha.first()).isEqualTo(0.15f)
        }
}
