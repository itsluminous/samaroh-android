package com.itsluminous.samaroh.feature.menu.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.google.backup.BackupFrequency
import com.itsluminous.samaroh.core.google.backup.BackupScheduler
import com.itsluminous.samaroh.core.google.calendar.CalendarSyncScheduler
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.SettingsPermissions
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.menu.data.CurrentBusinessProvider
import com.itsluminous.samaroh.feature.menu.data.SettingsPreferencesDataSource
import com.itsluminous.samaroh.feature.menu.fakes.FakeBusinessRepository
import com.itsluminous.samaroh.feature.menu.fakes.FakeGoogleAccountLinker
import com.itsluminous.samaroh.feature.menu.fakes.FakePermissionGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Clock.fixed(Instant.parse("2026-08-25T09:00:00Z"), ZoneOffset.UTC)

    private lateinit var storeScope: CoroutineScope
    private lateinit var businessRepository: FakeBusinessRepository
    private lateinit var permissionGuard: FakePermissionGuard
    private lateinit var linker: FakeGoogleAccountLinker
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        storeScope = CoroutineScope(dispatcherRule.dispatcher + Job())
        val dataStore =
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(context.cacheDir, "settings-vm-${System.nanoTime()}.preferences_pb")
            }
        businessRepository = FakeBusinessRepository(initialBusinesses = listOf(Fixtures.business()))
        permissionGuard = FakePermissionGuard()
        linker = FakeGoogleAccountLinker()
        viewModel =
            SettingsViewModel(
                currentBusinessProvider = CurrentBusinessProvider(businessRepository),
                preferences = SettingsPreferencesDataSource(dataStore),
                googleAccountLinker = linker,
                permissionGuard = permissionGuard,
                businessRepository = businessRepository,
                backupScheduler = BackupScheduler(context),
                calendarSyncScheduler = CalendarSyncScheduler(context),
                clock = clock,
            )
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `backup section is owner-only`() =
        runTest(dispatcherRule.dispatcher) {
            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()

            permissionGuard.ownerFlow.value = false
            runCurrent()
            assertThat(viewModel.uiState.value.isOwner).isFalse()

            permissionGuard.ownerFlow.value = true
            runCurrent()
            assertThat(viewModel.uiState.value.isOwner).isTrue()
            collector.cancel()
        }

    @Test
    fun `gcal toggle is gated by the gcal_sync permission or ownership`() =
        runTest(dispatcherRule.dispatcher) {
            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()
            assertThat(viewModel.uiState.value.canToggleGcalSync).isFalse()

            permissionGuard.permissionsFlow.value =
                MemberPermissions(settings = SettingsPermissions(gcalSync = true))
            runCurrent()
            assertThat(viewModel.uiState.value.canToggleGcalSync).isTrue()

            permissionGuard.permissionsFlow.value = MemberPermissions()
            permissionGuard.ownerFlow.value = true
            runCurrent()
            assertThat(viewModel.uiState.value.canToggleGcalSync).isTrue()
            collector.cancel()
        }

    @Test
    fun `enabling gcal sync persists the business setting`() =
        runTest(dispatcherRule.dispatcher) {
            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()

            viewModel.setGcalSyncEnabled(true)
            runCurrent()

            val saved = businessRepository.settings(Fixtures.BUSINESS_ID).first()
            assertThat(saved?.gcalSyncEnabled).isTrue()
            assertThat(viewModel.showRemoveEventsDialog.value).isFalse()
            collector.cancel()
        }

    @Test
    fun `disabling gcal sync persists and offers the remove-events option`() =
        runTest(dispatcherRule.dispatcher) {
            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()

            viewModel.setGcalSyncEnabled(true)
            runCurrent()
            viewModel.setGcalSyncEnabled(false)
            runCurrent()

            assertThat(businessRepository.settings(Fixtures.BUSINESS_ID).first()?.gcalSyncEnabled).isFalse()
            assertThat(viewModel.showRemoveEventsDialog.value).isTrue()

            viewModel.onRemoveEventsChoice(removeEvents = false)
            assertThat(viewModel.showRemoveEventsDialog.value).isFalse()
            collector.cancel()
        }

    @Test
    fun `backup frequency persists its wire value`() =
        runTest(dispatcherRule.dispatcher) {
            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()

            viewModel.setBackupFrequency(BackupFrequency.DAILY)
            runCurrent()

            assertThat(businessRepository.settings(Fixtures.BUSINESS_ID).first()?.backupFrequency).isEqualTo("daily")
            assertThat(viewModel.uiState.value.backupFrequency).isEqualTo(BackupFrequency.DAILY)
            collector.cancel()
        }

    @Test
    fun `link failure surfaces a localized message`() =
        runTest(dispatcherRule.dispatcher) {
            linker.linkResult =
                Result.failure(
                    com.itsluminous.samaroh.core.google.auth.GoogleLinkException
                        .NotSignedIn(),
                )
            viewModel.linkGoogle(context)
            runCurrent()
            assertThat(viewModel.message.value)
                .isEqualTo(com.itsluminous.samaroh.core.i18n.R.string.settings_google_not_signed_in)

            viewModel.onMessageShown()
            assertThat(viewModel.message.value).isNull()
        }

    @Test
    fun `successful link updates state and unlink reverts it`() =
        runTest(dispatcherRule.dispatcher) {
            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()

            viewModel.linkGoogle(context)
            runCurrent()
            assertThat(viewModel.uiState.value.linkState)
                .isInstanceOf(com.itsluminous.samaroh.core.google.auth.GoogleLinkState.Linked::class.java)

            viewModel.unlinkGoogle()
            runCurrent()
            assertThat(viewModel.uiState.value.linkState)
                .isEqualTo(com.itsluminous.samaroh.core.google.auth.GoogleLinkState.NotLinked)
            assertThat(linker.unlinkCalls).isEqualTo(1)
            collector.cancel()
        }
}
