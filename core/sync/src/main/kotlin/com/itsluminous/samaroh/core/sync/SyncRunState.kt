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
        private val pullActive = MutableStateFlow(false)

        /** True while a sync run (push+pull) is actively executing. */
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        /**
         * True only while the PULL PHASE is applying rows to Room (ADR-060). This is the
         * window in which the replica is mid-mutation (bookings may land before their
         * payments); [com.itsluminous.samaroh.core.sync.DefaultReplicaIntegrity] gates on
         * THIS rather than [isRunning] so post-sync hooks — which run inside the same
         * sync run, after the pull completed and its integrity stamp was recorded — see
         * a consistent replica and can plan immediately.
         */
        val isPullActive: StateFlow<Boolean> = pullActive.asStateFlow()

        fun setRunning(value: Boolean) {
            running.value = value
        }

        fun setPullActive(value: Boolean) {
            pullActive.value = value
        }
    }
