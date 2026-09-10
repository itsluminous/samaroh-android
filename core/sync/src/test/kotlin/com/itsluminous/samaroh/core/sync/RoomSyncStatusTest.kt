package com.itsluminous.samaroh.core.sync

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.ConflictResolution
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.database.entity.SyncConflictEntity
import com.itsluminous.samaroh.core.database.entity.SyncCursorEntity
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
    private val syncRunState = SyncRunState()

    @Before
    fun setUp() {
        db = newTestDatabase()
        scheduler = RecordingScheduler()
        status =
            RoomSyncStatus(
                db.outboxDao(),
                db.syncConflictDao(),
                db.syncCursorDao(),
                InMemorySyncMetaStore(),
                scheduler,
                syncRunState,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `isSyncing mirrors the worker's run state`() =
        runTest {
            assertThat(status.isSyncing.first()).isFalse()

            syncRunState.setRunning(true)
            assertThat(status.isSyncing.first()).isTrue()

            syncRunState.setRunning(false)
            assertThat(status.isSyncing.first()).isFalse()
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

    @Test
    fun `discardItem removes the outbox row, resets the table cursor and requests a sync`() =
        runTest {
            // A business-scoped table's cursor keyed by the payload's business_id.
            db.syncCursorDao().upsert(
                SyncCursorEntity(
                    businessId = "biz-1",
                    tableName = "business_settings",
                    lastPulledAt = FIXED_NOW,
                    lastPulledId = "biz-1",
                    lastPulledRaw = FIXED_NOW.toString(),
                ),
            )
            val id =
                db.outboxDao().enqueue(
                    OutboxEntity(
                        entityType = "business_settings",
                        entityId = "biz-1",
                        operation = "upsert",
                        payloadJson = """{"business_id":"biz-1","gcal_sync_enabled":true}""",
                        createdAt = FIXED_NOW,
                    ),
                )
            db.outboxDao().recordFailure(id, "row-level security violation")

            status.discardItem(id)

            assertThat(status.pendingCount.first()).isEqualTo(0)
            assertThat(status.itemErrors.first()).isEmpty()
            assertThat(db.syncCursorDao().cursor("biz-1", "business_settings")).isNull()
            assertThat(scheduler.immediateRequests).isEqualTo(1)
        }

    @Test
    fun `discardItem on a global table resets the global-scope cursor`() =
        runTest {
            db.syncCursorDao().upsert(
                SyncCursorEntity(
                    businessId = SyncCursorEntity.GLOBAL_SCOPE,
                    tableName = "businesses",
                    lastPulledAt = FIXED_NOW,
                    lastPulledId = "biz-1",
                    lastPulledRaw = FIXED_NOW.toString(),
                ),
            )
            val id =
                db.outboxDao().enqueue(
                    OutboxEntity(
                        entityType = "businesses",
                        entityId = "biz-1",
                        operation = "upsert",
                        payloadJson = """{"id":"biz-1","name":"Renamed by a viewer"}""",
                        createdAt = FIXED_NOW,
                    ),
                )
            db.outboxDao().recordFailure(id, "row-level security violation")

            status.discardItem(id)

            assertThat(status.itemErrors.first()).isEmpty()
            assertThat(db.syncCursorDao().cursor(SyncCursorEntity.GLOBAL_SCOPE, "businesses")).isNull()
            assertThat(scheduler.immediateRequests).isEqualTo(1)
        }

    @Test
    fun `discardItem leaves other queued items untouched and tolerates a missing id`() =
        runTest {
            val keep =
                db.outboxDao().enqueue(
                    OutboxEntity(
                        entityType = "bookings",
                        entityId = "b-1",
                        operation = "upsert",
                        payloadJson = """{"id":"b-1","business_id":"biz-1"}""",
                        createdAt = FIXED_NOW,
                    ),
                )
            val drop =
                db.outboxDao().enqueue(
                    OutboxEntity(
                        entityType = "businesses",
                        entityId = "biz-1",
                        operation = "upsert",
                        payloadJson = """{"id":"biz-1"}""",
                        createdAt = FIXED_NOW,
                    ),
                )

            status.discardItem(drop)
            status.discardItem(drop + 999) // Unknown id: silent no-op, no crash.

            val remaining = status.pendingItems.first()
            assertThat(remaining).hasSize(1)
            assertThat(remaining.single().outboxId).isEqualTo(keep)
        }
}
