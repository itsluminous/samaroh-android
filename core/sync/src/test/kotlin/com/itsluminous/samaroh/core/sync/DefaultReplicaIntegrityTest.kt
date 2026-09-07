package com.itsluminous.samaroh.core.sync

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.sync.engine.FakeRemoteStore
import com.itsluminous.samaroh.core.sync.engine.InMemorySyncMetaStore
import com.itsluminous.samaroh.core.sync.remote.RemoteStore
import com.itsluminous.samaroh.core.sync.remote.RemoteStoreProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/** Decision table of the ADR-060 replica-consistency signal. */
class DefaultReplicaIntegrityTest {
    private val meta = InMemorySyncMetaStore()
    private val runState = SyncRunState()
    private val user = MutableStateFlow<String?>("user-1")

    private fun integrity(remote: RemoteStore? = FakeRemoteStore()): DefaultReplicaIntegrity =
        DefaultReplicaIntegrity(
            remoteStoreProvider = RemoteStoreProvider { remote },
            currentUserProvider =
                object : CurrentUserProvider {
                    override val currentUserId: Flow<String?> = user
                },
            runState = runState,
            metaStore = meta,
        )

    @Test
    fun `unconfigured build is always consistent - Room is the single source of truth`() =
        runTest {
            assertThat(integrity(remote = null).isReplicaConsistent()).isTrue()
        }

    @Test
    fun `signed-out usage is consistent - nothing is ever pulled`() =
        runTest {
            user.value = null
            assertThat(integrity().isReplicaConsistent()).isTrue()
        }

    @Test
    fun `signed in with no completed pull yet is INCONSISTENT - the fresh sign-in window`() =
        runTest {
            assertThat(integrity().isReplicaConsistent()).isFalse()
        }

    @Test
    fun `an ACTIVE pull phase is INCONSISTENT even after a past complete pull`() =
        runTest {
            meta.recordCompletePullTime(Instant.parse("2026-09-07T10:00:00Z"))
            runState.setPullActive(true)
            assertThat(integrity().isReplicaConsistent()).isFalse()
        }

    @Test
    fun `a running sync OUTSIDE the pull phase stays consistent - post-sync hooks must plan immediately`() =
        runTest {
            // The reminder hook runs INSIDE the sync run (after the pull + its stamp);
            // gating on the whole run would block every post-sync re-plan forever.
            meta.recordCompletePullTime(Instant.parse("2026-09-07T10:00:00Z"))
            runState.setRunning(true)
            runState.setPullActive(false)
            assertThat(integrity().isReplicaConsistent()).isTrue()
        }

    @Test
    fun `an incomplete pull AFTER the last complete one is INCONSISTENT until the next clean pull`() =
        runTest {
            meta.recordCompletePullTime(Instant.parse("2026-09-07T10:00:00Z"))
            meta.recordIncompletePullTime(Instant.parse("2026-09-07T10:05:00Z"))
            assertThat(integrity().isReplicaConsistent()).isFalse()

            meta.recordCompletePullTime(Instant.parse("2026-09-07T10:10:00Z"))
            assertThat(integrity().isReplicaConsistent()).isTrue()
        }

    @Test
    fun `a clean completed pull with no run in flight is consistent`() =
        runTest {
            meta.recordCompletePullTime(Instant.parse("2026-09-07T10:00:00Z"))
            assertThat(integrity().isReplicaConsistent()).isTrue()
        }
}
