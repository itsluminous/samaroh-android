package com.itsluminous.samaroh.feature.menu.ui.settings

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkException
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.backup.BackupFrequency
import com.itsluminous.samaroh.core.google.backup.BackupScheduler
import com.itsluminous.samaroh.core.google.calendar.CalendarSyncScheduler
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.feature.menu.data.CurrentBusinessProvider
import com.itsluminous.samaroh.feature.menu.data.DeviceSettings
import com.itsluminous.samaroh.feature.menu.data.SettingsPreferencesDataSource
import com.itsluminous.samaroh.feature.menu.data.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/** Settings screen state (§4.4). */
data class SettingsUiState(
    val loading: Boolean = true,
    val businessId: String? = null,
    val businessName: String? = null,
    val device: DeviceSettings? = null,
    val linkState: GoogleLinkState = GoogleLinkState.NotLinked,
    /** `settings.gcal_sync` permission or owner (§3). */
    val canToggleGcalSync: Boolean = false,
    val gcalSyncEnabled: Boolean = false,
    /** Backups are owner-only — the whole section hides otherwise (§4.4). */
    val isOwner: Boolean = false,
    val backupFrequency: BackupFrequency = BackupFrequency.WEEKLY,
    val lastBackupAt: Instant? = null,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        currentBusinessProvider: CurrentBusinessProvider,
        private val preferences: SettingsPreferencesDataSource,
        private val googleAccountLinker: GoogleAccountLinker,
        private val permissionGuard: PermissionGuard,
        private val businessRepository: BusinessRepository,
        private val backupScheduler: BackupScheduler,
        private val calendarSyncScheduler: CalendarSyncScheduler,
        private val clock: Clock,
    ) : ViewModel() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<SettingsUiState> =
            currentBusinessProvider.currentBusiness
                .flatMapLatest { business ->
                    if (business == null) {
                        combine(preferences.settings, googleAccountLinker.linkState) { device, link ->
                            SettingsUiState(loading = false, device = device, linkState = link)
                        }
                    } else {
                        combine(
                            preferences.settings,
                            googleAccountLinker.linkState,
                            businessRepository.settings(business.id),
                            permissionGuard.permissions(business.id),
                            permissionGuard.isOwner(business.id),
                        ) { device, link, settings, permissions, isOwner ->
                            SettingsUiState(
                                loading = false,
                                businessId = business.id,
                                businessName = business.name,
                                device = device,
                                linkState = link,
                                canToggleGcalSync = isOwner || permissions.settings.gcalSync,
                                gcalSyncEnabled = settings?.gcalSyncEnabled == true,
                                isOwner = isOwner,
                                backupFrequency = BackupFrequency.fromWire(settings?.backupFrequency ?: BackupFrequency.WEEKLY.wire),
                                lastBackupAt = settings?.lastBackupAt,
                            )
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

        /** One-shot snackbar message (string resource id); cleared via [onMessageShown]. */
        private val _message = MutableStateFlow<Int?>(null)
        val message: StateFlow<Int?> = _message.asStateFlow()

        /** Pending Google scope-consent sheet the screen must launch, then call [completeGoogleConsent]. */
        private val _consentIntent = MutableStateFlow<PendingIntent?>(null)
        val consentIntent: StateFlow<PendingIntent?> = _consentIntent.asStateFlow()

        /** §4.1 disable option: "remove synced events" dialog visibility. */
        private val _showRemoveEventsDialog = MutableStateFlow(false)
        val showRemoveEventsDialog: StateFlow<Boolean> = _showRemoveEventsDialog.asStateFlow()

        fun onMessageShown() {
            _message.value = null
        }

        fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch { preferences.setThemeMode(mode) }
        }

        fun setDynamicColor(enabled: Boolean) {
            viewModelScope.launch { preferences.setDynamicColor(enabled) }
        }

        // Booking-form field visibility (ADR-020, Settings → Booking form fields).

        fun setBookingFormShowDeposit(show: Boolean) {
            viewModelScope.launch { preferences.setBookingFormShowDeposit(show) }
        }

        fun setBookingFormShowSource(show: Boolean) {
            viewModelScope.launch { preferences.setBookingFormShowSource(show) }
        }

        fun setBookingFormShowTimes(show: Boolean) {
            viewModelScope.launch { preferences.setBookingFormShowTimes(show) }
        }

        fun linkGoogle(activityContext: Context) {
            viewModelScope.launch {
                googleAccountLinker.link(activityContext).onFailure(::handleLinkFailure)
            }
        }

        fun completeGoogleConsent(resultIntent: Intent?) {
            _consentIntent.value = null
            viewModelScope.launch {
                googleAccountLinker.completeLink(resultIntent).onFailure(::handleLinkFailure)
            }
        }

        fun unlinkGoogle() {
            viewModelScope.launch { googleAccountLinker.unlink() }
        }

        private fun handleLinkFailure(error: Throwable) {
            when (error) {
                is GoogleLinkException.NeedsScopeConsent -> _consentIntent.value = error.pendingIntent
                is GoogleLinkException.Cancelled -> Unit
                is GoogleLinkException.NotSignedIn -> _message.value = R.string.settings_google_not_signed_in
                is GoogleLinkException.NotConfigured -> _message.value = R.string.settings_google_not_configured
                else -> _message.value = R.string.settings_google_link_failed
            }
        }

        /** §4.1: enable → bulk push; disable → offer the remove-events option. */
        fun setGcalSyncEnabled(enabled: Boolean) {
            val businessId = uiState.value.businessId ?: return
            viewModelScope.launch {
                saveBusinessSettings(businessId) { it.copy(gcalSyncEnabled = enabled) }
                if (enabled) {
                    calendarSyncScheduler.requestSync(businessId)
                    calendarSyncScheduler.ensurePeriodicSync(businessId)
                } else {
                    _showRemoveEventsDialog.value = true
                }
            }
        }

        /** Answer to the disable dialog: keep or remove the already-pushed events. */
        fun onRemoveEventsChoice(removeEvents: Boolean) {
            _showRemoveEventsDialog.value = false
            val businessId = uiState.value.businessId ?: return
            calendarSyncScheduler.disable(businessId, removeEvents)
        }

        fun setBackupFrequency(frequency: BackupFrequency) {
            val businessId = uiState.value.businessId ?: return
            viewModelScope.launch {
                saveBusinessSettings(businessId) { it.copy(backupFrequency = frequency.wire) }
                backupScheduler.applyFrequency(businessId, frequency)
            }
        }

        fun backUpNow() {
            val businessId = uiState.value.businessId ?: return
            backupScheduler.backUpNow(businessId)
            _message.value = R.string.settings_backup_started
        }

        private suspend fun saveBusinessSettings(
            businessId: String,
            mutate: (BusinessSettings) -> BusinessSettings,
        ) {
            val now = clock.instant()
            val current =
                businessRepository.settings(businessId).first()
                    ?: BusinessSettings(businessId = businessId, updatedAt = now)
            businessRepository.saveSettings(mutate(current).copy(updatedAt = now))
        }
    }
