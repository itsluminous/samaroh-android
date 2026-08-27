package com.itsluminous.samaroh.core.sync

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.ConflictResolution
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.database.entity.SyncConflictEntity
import com.itsluminous.samaroh.core.sync.engine.FIXED_NOW
import com.itsluminous.samaroh.core.sync.engine.InMemorySyncMetaStore
import com.itsluminous.samaroh.core.sync.engine.newTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomSyncStatusTest {
    private class RecordingScheduler : SyncScheduler {
        var immediateRequests = 0
        var periodicRequests = 0

        override fun requestImmediateSync() {
            immediateRequests++
        }

        override fun ensurePeriodicSync() {
            periodicRequests++
        }
    }

    private lateinit var db: SamarohDatabase
    private lateinit var scheduler: RecordingScheduler
    private lateinit var status: RoomSyncStatus

    @Before
    fun setUp() {
        db = newTestDatabase()
        scheduler = RecordingScheduler()
        status = RoomSyncStatus(db.outboxDao(), db.syncConflictDao(), InMemorySyncMetaStore(), scheduler)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `pending count and item errors reflect the outbox`() =
        runTest {
            val id =
                db.outboxDao().enqueue(
                    OutboxEntity(
                        entityType = "bookings",
                        entityId = "b-1",
                        operation = "upsert",
                        payloadJson = """{"id":"b-1"}""",
                        createdAt = FIXED_NOW,
                    ),
                )
            db.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "parties",
                    entityId = "p-1",
                    operation = "delete",
                    payloadJson = """{"id":"p-1"}""",
                    createdAt = FIXED_NOW,
                ),
            )
            db.outboxDao().recordFailure(id, "row-level security violation")

            assertThat(status.pendingCount.first()).isEqualTo(2)
            val pending = status.pendingItems.first()
            assertThat(pending).hasSize(2)
            with(pending.first()) {
                assertThat(outboxId).isEqualTo(id)
                assertThat(entityType).isEqualTo("bookings")
                assertThat(operation).isEqualTo(OutboxOperation.UPSERT)
                assertThat(payloadJson).isEqualTo("""{"id":"b-1"}""")
                assertThat(queuedAt).isEqualTo(FIXED_NOW)
            }
            assertThat(pending[1].operation).isEqualTo(OutboxOperation.DELETE)
            val errors = status.itemErrors.first()
            assertThat(errors).hasSize(1)
            with(errors.single()) {
                assertThat(entityType).isEqualTo("bookings")
                assertThat(operation).isEqualTo(OutboxOperation.UPSERT)
                assertThat(message).contains("security")
                assertThat(attemptCount).isEqualTo(1)
                assertThat(payloadJson).isEqualTo("""{"id":"b-1"}""")
            }
        }

    @Test
    fun `conflict log maps entries and acknowledging clears the banner`() =
        runTest {
            db.syncConflictDao().insert(
                SyncConflictEntity(
                    entityType = "bookings",
                    entityId = "b-1",
                    title = "conflict-title",
                    overriddenFields = "customer_name,notes",
                    resolution = "rebased",
                    occurredAt = FIXED_NOW,
                ),
            )

            assertThat(status.hasUnacknowledgedConflicts.first()).isTrue()
            val entry = status.conflictLog.first().single()
            assertThat(entry.overriddenFields).containsExactly("customer_name", "notes").inOrder()
            assertThat(entry.resolution).isEqualTo(ConflictResolution.REBASED)

            status.acknowledgeConflict(entry.id)

            assertThat(status.hasUnacknowledgedConflicts.first()).isFalse()
        }

    @Test
    fun `syncNow requests an immediate sync`() {
        status.syncNow()

        assertThat(scheduler.immediateRequests).isEqualTo(1)
    }
}
