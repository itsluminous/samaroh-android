package com.itsluminous.samaroh.feature.booking.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Test-button plumbing (ADR-045): the sample reminder must travel the PRODUCTION
 * pipeline — direct notification for the Simple style; the AlarmManager exact-alarm
 * path (which the real receiver turns into the full-screen notification) for the
 * full-screen style.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BookingReminderTestFirerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Clock.fixed(Instant.parse("2026-09-03T06:00:00Z"), ZoneOffset.UTC)

    private val dispatcher = UnconfinedTestDispatcher()
    private val storeScope = CoroutineScope(dispatcher + Job())

    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tmp.root, "settings.preferences_pb")
        }

    private fun firer(): BookingReminderTestFirer =
        BookingReminderTestFirer(
            context = context,
            notifier = BookingNotifier(context),
            prefs = BookingReminderPrefs(dataStore),
            clock = clock,
        )

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private suspend fun select(
        style: ReminderStyle,
        soundUri: String? = null,
    ) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("booking_reminder_style")] = style.wire
            soundUri?.let { prefs[stringPreferencesKey("booking_reminder_sound_uri")] = it }
        }
    }

    @Test
    fun `simple style posts the sample notification immediately, no alarm`() =
        runTest(dispatcher) {
            select(ReminderStyle.NOTIFICATION)

            firer().fireSample()

            val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            assertThat(posted).hasSize(1)
            assertThat(posted.single().fullScreenIntent).isNull()
            assertThat(shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm).isNull()
        }

    @Test
    fun `fullscreen style schedules the production exact alarm a few seconds out`() =
        runTest(dispatcher) {
            select(ReminderStyle.FULLSCREEN, soundUri = "content://media/test-ringtone")

            firer().fireSample()

            val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            assertThat(posted).isEmpty() // fires via the receiver, like production
            val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm
            assertThat(alarm).isNotNull()
            assertThat(checkNotNull(alarm).triggerAtTime)
                .isEqualTo(clock.millis() + BookingReminderTestFirer.FULLSCREEN_FIRE_DELAY_MS)
        }
}
