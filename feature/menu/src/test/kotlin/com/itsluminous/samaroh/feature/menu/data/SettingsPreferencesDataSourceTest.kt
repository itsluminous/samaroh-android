package com.itsluminous.samaroh.feature.menu.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
 * Round-trip of the device settings DataStore — asserts the EXACT preference keys of the
 * cross-feature contract (`booking_reminder_lead_days` / `booking_reminder_style` /
 * `booking_reminder_sound_uri` in file "settings").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPreferencesDataSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val storeScope = CoroutineScope(dispatcher + Job())

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tmp.root, "${SettingsPreferencesDataSource.FILE_NAME}.preferences_pb")
        }
    }
    private val dataSource by lazy { SettingsPreferencesDataSource(dataStore) }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `defaults are sensible before any write`() =
        testScope.runTest {
            val settings = dataSource.settings.first()
            assertThat(settings.themeMode).isEqualTo(ThemeMode.SYSTEM)
            assertThat(settings.dynamicColor).isTrue()
            assertThat(settings.reminderLeadDays).isEqualTo(SettingsPreferencesDataSource.DEFAULT_LEAD_DAYS)
            assertThat(settings.reminderStyle).isEqualTo(ReminderStyle.NOTIFICATION)
            assertThat(settings.reminderSoundUri).isNull()
        }

    @Test
    fun `reminder prefs round-trip through the exact contract keys`() =
        testScope.runTest {
            dataSource.setReminderLeadDays(setOf(7, 1, 3))
            dataSource.setReminderStyle(ReminderStyle.FULLSCREEN)
            dataSource.setReminderSoundUri("content://media/internal/audio/media/42")

            val settings = dataSource.settings.first()
            assertThat(settings.reminderLeadDays).containsExactly(1, 3, 7).inOrder()
            assertThat(settings.reminderStyle).isEqualTo(ReminderStyle.FULLSCREEN)
            assertThat(settings.reminderSoundUri).isEqualTo("content://media/internal/audio/media/42")

            // The raw keys ARE the contract (§4.4): exact names + types.
            val raw = dataStore.data.first()
            assertThat(raw[stringSetPreferencesKey("booking_reminder_lead_days")])
                .containsExactly("1", "3", "7")
            assertThat(raw[stringPreferencesKey("booking_reminder_style")]).isEqualTo("fullscreen")
            assertThat(raw[stringPreferencesKey("booking_reminder_sound_uri")])
                .isEqualTo("content://media/internal/audio/media/42")
        }

    @Test
    fun `reminder style wire values match the contract`() {
        assertThat(ReminderStyle.NOTIFICATION.wire).isEqualTo("notification")
        assertThat(ReminderStyle.FULLSCREEN.wire).isEqualTo("fullscreen")
        assertThat(ReminderStyle.fromWire("fullscreen")).isEqualTo(ReminderStyle.FULLSCREEN)
        assertThat(ReminderStyle.fromWire("bogus")).isEqualTo(ReminderStyle.NOTIFICATION)
    }

    @Test
    fun `theme prefs round-trip`() =
        testScope.runTest {
            dataSource.setThemeMode(ThemeMode.DARK)
            dataSource.setDynamicColor(false)

            val settings = dataSource.settings.first()
            assertThat(settings.themeMode).isEqualTo(ThemeMode.DARK)
            assertThat(settings.dynamicColor).isFalse()

            val raw = dataStore.data.first()
            assertThat(raw[stringPreferencesKey("theme_mode")]).isEqualTo("dark")
        }

    @Test
    fun `clearing the sound uri removes the key`() =
        testScope.runTest {
            dataSource.setReminderSoundUri("content://x")
            dataSource.setReminderSoundUri(null)
            val raw = dataStore.data.first()
            assertThat(raw.contains(stringPreferencesKey("booking_reminder_sound_uri"))).isFalse()
            assertThat(dataSource.settings.first().reminderSoundUri).isNull()
        }
}
