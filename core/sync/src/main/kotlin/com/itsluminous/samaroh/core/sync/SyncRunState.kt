package com.itsluminous.samaroh.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide "a sync run is executing right now" flag. [SyncWorker] raises it around
 * `SyncEngine.runSync()` (try/finally, so a crash never leaves it stuck) and
 * [RoomSyncStatus] republishes it as `SyncStatus.isSyncing` for the app-bar cloud icon
 * (§4.5). WorkManager serializes the unique sync work, so a plain boolean suffices.
 */
@Singleton
class SyncRunState
    @Inject
    constructor() {
        private val running = MutableStateFlow(false)

        /** True while a sync run (push+pull) is actively executing. */
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        fun setRunning(value: Boolean) {
            running.value = value
        }
    }
