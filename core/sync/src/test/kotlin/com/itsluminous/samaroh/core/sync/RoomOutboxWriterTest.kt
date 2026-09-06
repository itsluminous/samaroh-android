package com.itsluminous.samaroh.core.sync

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.LocalMutationListener
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.sync.engine.newTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * ADR-036: EVERY outbox write must nudge the debounced on-change sync — wired here, at
 * the [RoomOutboxWriter] level, so all features get push-within-seconds for free.
 * ADR-046: the multibound [LocalMutationListener]s are notified on the same path, and a
 * listener failure never fails the enqueuing write.
 */
@RunWith(RobolectricTestRunner::class)
class RoomOutboxWriterTest {
    private class RecordingScheduler : SyncScheduler {
        var onLocalChangeRequests = 0
        var immediateRequests = 0
        var periodicRequests = 0

        override fun requestImmediateSync() {
            immediateRequests++
        }

        override fun ensurePeriodicSync() {
            periodicRequests++
        }

        override fun requestSyncOnLocalChange() {
            onLocalChangeRequests++
        }
    }

    private class RecordingListener : LocalMutationListener {
        val mutations = mutableListOf<Triple<String, String, OutboxOperation>>()

        override suspend fun onLocalMutation(
            entityType: String,
            entityId: String,
            operation: OutboxOperation,
            payloadJson: String,
        ) {
            mutations += Triple(entityType, entityId, operation)
        }
    }

    private lateinit var db: SamarohDatabase
    private lateinit var scheduler: RecordingScheduler
    private lateinit var listener: RecordingListener
    private lateinit var writer: RoomOutboxWriter

    @Before
    fun setUp() {
        db = newTestDatabase()
        scheduler = RecordingScheduler()
        listener = RecordingListener()
        writer =
            RoomOutboxWriter(
                outboxDao = db.outboxDao(),
                syncScheduler = scheduler,
                listeners = { setOf(listener) },
                clock = Clock.fixed(Instant.parse("2026-08-28T06:00:00Z"), ZoneOffset.UTC),
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `enqueue writes the outbox row AND requests an on-change sync`() =
        runTest {
            writer.enqueue("bookings", "booking-1", OutboxOperation.UPSERT, "{}")

            val queued = db.outboxDao().nextBatch(10)
            assertThat(queued).hasSize(1)
            assertThat(queued.single().entityType).isEqualTo("bookings")
            assertThat(scheduler.onLocalChangeRequests).isEqualTo(1)
            // The debounced trigger, not the expedited or periodic path.
            assertThat(scheduler.immediateRequests).isEqualTo(0)
            assertThat(scheduler.periodicRequests).isEqualTo(0)
        }

    @Test
    fun `every write in a burst nudges the scheduler - debouncing is the scheduler's job`() =
        runTest {
            repeat(5) { writer.enqueue("expenses", "expense-$it", OutboxOperation.UPSERT, "{}") }

            assertThat(db.outboxDao().nextBatch(10)).hasSize(5)
            assertThat(scheduler.onLocalChangeRequests).isEqualTo(5)
        }

    @Test
    fun `every enqueue notifies the mutation listeners with the mutation details`() =
        runTest {
            writer.enqueue("bookings", "booking-1", OutboxOperation.UPSERT, """{"id":"booking-1"}""")
            writer.enqueue("bookings", "booking-1", OutboxOperation.DELETE, "{}")

            assertThat(listener.mutations)
                .containsExactly(
                    Triple("bookings", "booking-1", OutboxOperation.UPSERT),
                    Triple("bookings", "booking-1", OutboxOperation.DELETE),
                ).inOrder()
        }

    @Test
    fun `a throwing listener never fails the write`() =
        runTest {
            val throwing =
                LocalMutationListener { _, _, _, _ -> error("listener boom") }
            writer =
                RoomOutboxWriter(
                    outboxDao = db.outboxDao(),
                    syncScheduler = scheduler,
                    listeners = { setOf(throwing, listener) },
                    clock = Clock.fixed(Instant.parse("2026-08-28T06:00:00Z"), ZoneOffset.UTC),
                )

            writer.enqueue("bookings", "booking-1", OutboxOperation.UPSERT, "{}")

            assertThat(db.outboxDao().nextBatch(10)).hasSize(1)
            assertThat(listener.mutations).hasSize(1) // later listeners still run
        }
}
