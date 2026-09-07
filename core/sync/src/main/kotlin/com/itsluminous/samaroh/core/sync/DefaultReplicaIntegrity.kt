package com.itsluminous.samaroh.core.sync

import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.data.sync.ReplicaIntegrity
import com.itsluminous.samaroh.core.sync.remote.RemoteStoreProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `core:sync` [ReplicaIntegrity] signal (ADR-060). The replica is CONSISTENT when:
 * - Supabase is not configured (offline-only build — Room is the single source of
 *   truth), or
 * - nobody is signed in (offline-continue usage: nothing is ever pulled, so local data
 *   is complete by definition), or
 * - no pull phase is applying rows right now AND the most recent pull outcome was
 *   COMPLETE —
 *   every table pulled without a rejected/skipped table and without a transport abort
 *   ([SyncMetaStore.lastCompletePullTime] at or after [SyncMetaStore.lastIncompletePullTime]).
 *
 * A fresh signed-in install is inconsistent until its FIRST clean full pull — exactly
 * the window in which planning payment reminders from `total − Σpayments` fabricated
 * hundreds of bogus rows (bookings pulled, payments not). Both stamps live in the
 * session-scoped [SyncMetaStore], so sign-out resets the signal for the next account.
 */
@Singleton
class DefaultReplicaIntegrity
    @Inject
    constructor(
        private val remoteStoreProvider: RemoteStoreProvider,
        private val currentUserProvider: CurrentUserProvider,
        private val runState: SyncRunState,
        private val metaStore: SyncMetaStore,
    ) : ReplicaIntegrity {
        override suspend fun isReplicaConsistent(): Boolean {
            if (remoteStoreProvider.get() == null) return true
            if (currentUserProvider.currentUserId.first() == null) return true
            if (runState.isPullActive.value) return false
            val complete = metaStore.lastCompletePullTime.first() ?: return false
            val incomplete = metaStore.lastIncompletePullTime.first() ?: return true
            return !complete.isBefore(incomplete)
        }
    }
