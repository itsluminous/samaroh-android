package com.itsluminous.samaroh.feature.menu.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.reminders.ReminderTestFirer
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.menu.data.ReminderStyle
import com.itsluminous.samaroh.feature.menu.data.SettingsPreferencesDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Test-button plumbing on the settings side (ADR-045): the ViewModel forwards the
 * Test tap to the cross-feature [ReminderTestFirer] contract, and style changes land
 * in the shared DataStore the reminder engine reads at fire time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderSettingsViewModelTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    @get:Rule val tmp = TemporaryFolder()

    private class FakeReminderTestFirer : ReminderTestFirer {
        var fired = 0

        override suspend fun fireSample() {
            fired++
        }
    }

    private lateinit var storeScope: CoroutineScope
    private lateinit var preferences: SettingsPreferencesDataSource
    private lateinit var firer: FakeReminderTestFirer
    private lateinit var viewModel: ReminderSettingsViewModel

    @Before
    fun setUp() {
        storeScope = CoroutineScope(dispatcherRule.dispatcher + Job())
        val dataStore =
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tmp.root, "reminder-vm-${System.nanoTime()}.preferences_pb")
            }
        preferences = SettingsPreferencesDataSource(dataStore)
        firer = FakeReminderTestFirer()
        viewModel = ReminderSettingsViewModel(preferences, firer)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `fireTestReminder invokes the cross-feature firer`() =
        runTest(dispatcherRule.dispatcher) {
            viewModel.fireTestReminder()
            viewModel.fireTestReminder()

            assertThat(firer.fired).isEqualTo(2)
        }

    @Test
    fun `setStyle writes the wire value the reminder engine reads at fire time`() =
        runTest(dispatcherRule.dispatcher) {
            viewModel.setStyle(ReminderStyle.FULLSCREEN)

            assertThat(preferences.settings.first().reminderStyle).isEqualTo(ReminderStyle.FULLSCREEN)
        }
}
