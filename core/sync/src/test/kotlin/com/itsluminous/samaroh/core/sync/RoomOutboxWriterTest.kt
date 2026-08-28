package com.itsluminous.samaroh.core.sync

import com.google.common.truth.Truth.assertThat
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

    private lateinit var db: SamarohDatabase
    private lateinit var scheduler: RecordingScheduler
    private lateinit var writer: RoomOutboxWriter

    @Before
    fun setUp() {
        db = newTestDatabase()
        scheduler = RecordingScheduler()
        writer =
            RoomOutboxWriter(
                outboxDao = db.outboxDao(),
                syncScheduler = scheduler,
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
}
