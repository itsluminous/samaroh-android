package com.itsluminous.samaroh.core.sync.engine

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.SyncAuthState
import com.itsluminous.samaroh.core.data.sync.SyncErrorCodes
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.sync.RoomSyncStatus
import com.itsluminous.samaroh.core.sync.SyncRunState
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-089: the engine never talks to the server without a user session. Without one,
 * RLS rejects every push (`42501`) and filters every pull to nothing — the run used to
 * "succeed" while nothing synced.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineAuthGateTest {
    private lateinit var db: SamarohDatabase
    private lateinit var remote: FakeRemoteStore
    private lateinit var gate: FakeSyncAuthGate

    @Before
    fun setUp() {
        db = newTestDatabase()
        remote = FakeRemoteStore()
        gate = FakeSyncAuthGate()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `signed out - no network, queued ops held as waiting for sign-in`() =
        runTest {
            gate.state.value = SyncAuthState.SIGNED_OUT
            db.outboxDao().enqueue(bookingOutboxEntry(Fixtures.booking(id = "b-1")))
            // A stale anon-role rejection from before the guard existed.
            db.outboxDao().enqueue(
                bookingOutboxEntry(Fixtures.booking(id = "b-2")).copy(lastError = "42501 row-level security", attemptCount = 3),
            )

            val outcome = syncEngine(db, remote, authGate = gate).runSync()

            assertThat(outcome.configured).isTrue()
            assertThat(outcome.authState).isEqualTo(SyncAuthState.SIGNED_OUT)
            assertThat(outcome.networkFailed).isFalse()
            assertThat(remote.upserts).isEmpty()
            assertThat(remote.pullCalls).isEmpty()
            val rows = db.outboxDao().nextBatch()
            assertThat(rows).hasSize(2)
            assertThat(rows.map { it.lastError }).containsExactly(
                SyncErrorCodes.WAITING_FOR_SIGN_IN,
                SyncErrorCodes.WAITING_FOR_SIGN_IN,
            )
            // Holding is not an attempt.
            assertThat(rows.map { it.attemptCount }).containsExactly(0, 3)
        }

    @Test
    fun `no account - same hold, nothing pushed as anon`() =
        runTest {
            gate.state.value = SyncAuthState.NO_ACCOUNT
            db.outboxDao().enqueue(bookingOutboxEntry(Fixtures.booking(id = "b-1")))

            val outcome = syncEngine(db, remote, authGate = gate).runSync()

            assertThat(outcome.authState).isEqualTo(SyncAuthState.NO_ACCOUNT)
            assertThat(remote.upserts).isEmpty()
            assertThat(remote.pullCalls).isEmpty()
            assertThat(
                db
                    .outboxDao()
                    .nextBatch()
                    .single()
                    .lastError,
            ).isEqualTo(SyncErrorCodes.WAITING_FOR_SIGN_IN)
        }

    @Test
    fun `refresh pending - retry later, queue untouched`() =
        runTest {
            gate.state.value = SyncAuthState.REFRESH_PENDING
            db.outboxDao().enqueue(bookingOutboxEntry(Fixtures.booking(id = "b-1")))

            val outcome = syncEngine(db, remote, authGate = gate).runSync()

            assertThat(outcome.networkFailed).isTrue()
            assertThat(remote.upserts).isEmpty()
            assertThat(
                db
                    .outboxDao()
                    .nextBatch()
                    .single()
                    .lastError,
            ).isNull()
        }

    @Test
    fun `self-heal - held ops drain on the first run after sign-in`() =
        runTest {
            gate.state.value = SyncAuthState.SIGNED_OUT
            db.outboxDao().enqueue(bookingOutboxEntry(Fixtures.booking(id = "b-1")))
            val engine = syncEngine(db, remote, authGate = gate)
            engine.runSync()
            assertThat(
                db
                    .outboxDao()
                    .nextBatch()
                    .single()
                    .lastError,
            ).isEqualTo(SyncErrorCodes.WAITING_FOR_SIGN_IN)

            gate.state.value = SyncAuthState.SIGNED_IN
            val outcome = engine.runSync()

            assertThat(outcome.authState).isEqualTo(SyncAuthState.SIGNED_IN)
            assertThat(outcome.pushedCount).isEqualTo(1)
            assertThat(remote.upserts.single().first).isEqualTo("bookings")
            assertThat(db.outboxDao().nextBatch()).isEmpty()
        }

    @Test
    fun `pre-flight awaits the settled auth state before any request`() =
        runTest {
            db.outboxDao().enqueue(bookingOutboxEntry(Fixtures.booking(id = "b-1")))

            syncEngine(db, remote, authGate = gate).runSync()

            assertThat(gate.awaitCalls).isEqualTo(1)
            assertThat(remote.upserts).hasSize(1)
        }

    @Test
    fun `held rows are pending, not errors, in the status read model`() =
        runTest {
            db.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "bookings",
                    entityId = "b-1",
                    operation = "upsert",
                    payloadJson = "{}",
                    lastError = SyncErrorCodes.WAITING_FOR_SIGN_IN,
                    createdAt = FIXED_NOW,
                ),
            )
            db.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "bookings",
                    entityId = "b-2",
                    operation = "upsert",
                    payloadJson = "{}",
                    lastError = "42501 denied",
                    createdAt = FIXED_NOW,
                ),
            )
            val status =
                RoomSyncStatus(
                    db.outboxDao(),
                    db.syncConflictDao(),
                    db.syncCursorDao(),
                    InMemorySyncMetaStore(),
                    object : SyncScheduler {
                        override fun requestImmediateSync() = Unit

                        override fun ensurePeriodicSync() = Unit
                    },
                    SyncRunState(),
                    gate,
                )

            val errors = status.itemErrors.first()
            val pending = status.pendingItems.first()

            assertThat(errors.map { it.entityId }).containsExactly("b-2")
            assertThat(pending.map { it.entityId }).containsExactly("b-1", "b-2").inOrder()
            assertThat(status.pendingCount.first()).isEqualTo(2)
        }
}
