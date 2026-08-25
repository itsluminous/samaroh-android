package com.itsluminous.samaroh.feature.menu.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.data.sync.SyncStatus
import com.itsluminous.samaroh.core.data.sync.SyncStatusProvider
import com.itsluminous.samaroh.feature.menu.data.ReminderStyle
import com.itsluminous.samaroh.feature.menu.data.SettingsPreferencesDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Booking-reminder preferences screen (§4.4: lead times, style, sound). */
@HiltViewModel
class ReminderSettingsViewModel
    @Inject
    constructor(
        private val preferences: SettingsPreferencesDataSource,
    ) : ViewModel() {
        val settings =
            preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        fun toggleLeadDay(day: Int) {
            viewModelScope.launch {
                val current = preferences.settings.first().reminderLeadDays
                val updated = if (day in current) current - day else current + day
                // At least one lead time must stay selected — reminders can't be "on with no time".
                if (updated.isNotEmpty()) preferences.setReminderLeadDays(updated)
            }
        }

        fun addCustomLeadDay(day: Int) {
            if (day < 1) return
            viewModelScope.launch {
                val current = preferences.settings.first().reminderLeadDays
                preferences.setReminderLeadDays(current + day)
            }
        }

        fun setStyle(style: ReminderStyle) {
            viewModelScope.launch { preferences.setReminderStyle(style) }
        }

        fun setSoundUri(uri: String?) {
            viewModelScope.launch { preferences.setReminderSoundUri(uri) }
        }
    }

/** Sync status screen (§4.4/§4.5): pending count, per-item errors, last sync, Sync now. */
@HiltViewModel
class SyncStatusViewModel
    @Inject
    constructor(
        syncStatusProvider: SyncStatusProvider,
        private val syncScheduler: SyncScheduler,
    ) : ViewModel() {
        val status: StateFlow<SyncStatus?> =
            syncStatusProvider.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        fun syncNow() {
            syncScheduler.requestImmediateSync()
        }
    }
