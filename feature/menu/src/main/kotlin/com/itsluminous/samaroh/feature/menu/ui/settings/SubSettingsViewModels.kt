package com.itsluminous.samaroh.feature.menu.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.sync.SyncConflictEntry
import com.itsluminous.samaroh.core.data.sync.SyncStatus
import com.itsluminous.samaroh.feature.menu.data.ReminderStyle
import com.itsluminous.samaroh.feature.menu.data.SettingsPreferencesDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

/** One queued change resolved for display: human line + technical detail on expand. */
data class PendingSyncRow(
    val outboxId: Long,
    val display: SyncEntryDisplay,
    val entityType: String,
    val operationWire: String,
    val entityId: String,
    val queuedAt: java.time.Instant,
)

/** One failed sync item resolved for display; keeps its sanitized error message. */
data class SyncErrorRow(
    val outboxId: Long,
    val display: SyncEntryDisplay,
    val message: String,
    val entityType: String,
    val operationWire: String,
    val entityId: String,
)

/** Sync-status screen state (§4.4/§4.5) assembled from the `core:data` [SyncStatus] flows. */
data class SyncStatusUiState(
    val pending: List<PendingSyncRow>,
    val errors: List<SyncErrorRow>,
    val conflicts: List<SyncConflictEntry>,
    val lastSyncAt: java.time.Instant?,
) {
    val pendingCount: Int get() = pending.size
}

/** Sync status screen (§4.4/§4.5): pending list, per-item errors, conflict log, last sync, Sync now. */
@HiltViewModel
class SyncStatusViewModel
    @Inject
    constructor(
        private val syncStatus: SyncStatus,
        private val displayResolver: SyncEntryDisplayResolver,
    ) : ViewModel() {
        val status: StateFlow<SyncStatusUiState?> =
            combine(
                syncStatus.pendingItems,
                syncStatus.itemErrors,
                syncStatus.conflictLog,
                syncStatus.lastSyncTime,
            ) { pending, errors, conflicts, lastSync ->
                SyncStatusUiState(
                    pending =
                        pending.map { item ->
                            PendingSyncRow(
                                outboxId = item.outboxId,
                                display = displayResolver.resolve(item.entityType, item.operation, item.payloadJson, item.entityId),
                                entityType = item.entityType,
                                operationWire = item.operation.wire,
                                entityId = item.entityId,
                                queuedAt = item.queuedAt,
                            )
                        },
                    errors =
                        errors.map { error ->
                            SyncErrorRow(
                                outboxId = error.outboxId,
                                display = displayResolver.resolve(error.entityType, error.operation, error.payloadJson, error.entityId),
                                message = error.message,
                                entityType = error.entityType,
                                operationWire = error.operation.wire,
                                entityId = error.entityId,
                            )
                        },
                    conflicts = conflicts,
                    lastSyncAt = lastSync,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        fun syncNow() {
            syncStatus.syncNow()
        }

        fun acknowledgeConflict(id: Long) {
            viewModelScope.launch { syncStatus.acknowledgeConflict(id) }
        }
    }
