package com.itsluminous.samaroh.core.sync.engine

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.core.model.EventTypeKind
import com.itsluminous.samaroh.core.sync.remote.RemoteRejectedException
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * `event_types` sync wiring (ADR-032): pulls apply to Room, pushes carry the full
 * payload, and a server that does NOT have the table yet (shared migration 006
 * unapplied → PostgREST rejection) fails ONLY that table's pull — every other table
 * still syncs and the run completes.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineEventTypesTest {
    private lateinit var db: SamarohDatabase
    private lateinit var remote: FakeRemoteStore
    private lateinit var notifier: RecordingConflictNotifier

    @Before
    fun setUp() {
        db = newTestDatabase()
        remote = FakeRemoteStore()
        notifier = RecordingConflictNotifier()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBusiness() {
        db.businessDao().upsert(
            com.itsluminous.samaroh.core.database.entity
                .BusinessEntity(
                    id = Fixtures.BUSINESS_ID,
                    name = "fixture-business",
                    ownerName = "fixture-owner",
                    ownerUserId = Fixtures.USER_ID,
                    createdAt = Fixtures.NOW,
                    updatedAt = Fixtures.NOW,
                ),
        )
    }

    private fun remotePresetRow(
        id: String,
        label: String,
        updatedAt: String,
        color: String? = "tomato",
        sortOrder: Int = 0,
    ) = buildJsonObject {
        put("id", id)
        put("business_id", Fixtures.BUSINESS_ID)
        put("label", label)
        put("icon", "💒")
        color?.let { put("color", it) }
        put("sort_order", sortOrder)
        put("created_at", updatedAt)
        put("updated_at", updatedAt)
    }

    @Test
    fun `pulled event_types rows land in room`() =
        runTest {
            seedBusiness()
            remote.servePage(
                "event_types",
                listOf(remotePresetRow("et-1", "Wedding", updatedAt = "2026-08-25T10:00:00+00:00", sortOrder = 2)),
            )

            syncEngine(db, remote, notifier).runSync()

            val pulled = db.eventTypeDao().byId("et-1")
            assertThat(pulled).isNotNull()
            assertThat(pulled!!.label).isEqualTo("Wedding")
            assertThat(pulled.color).isEqualTo("tomato")
            assertThat(pulled.sortOrder).isEqualTo(2)
            // The row above carries NO `kind` key (a server without the ADR-041
            // column): the defaulted decode lands it as an ordinary booking preset.
            assertThat(pulled.kind).isEqualTo(EventTypeKind.BOOKING)
        }

    @Test
    fun `pulled marker kind lands as marker`() =
        runTest {
            seedBusiness()
            val row =
                buildJsonObject {
                    remotePresetRow("et-m", "Lagan", updatedAt = "2026-08-25T10:00:00+00:00").forEach { (k, v) -> put(k, v) }
                    put("kind", "marker")
                }
            remote.servePage("event_types", listOf(row))

            syncEngine(db, remote, notifier).runSync()

            assertThat(db.eventTypeDao().byId("et-m")!!.kind).isEqualTo(EventTypeKind.MARKER)
        }

    @Test
    fun `outbox event_types upserts push the full payload`() =
        runTest {
            seedBusiness()
            val preset =
                EventType(
                    id = "et-push",
                    businessId = Fixtures.BUSINESS_ID,
                    label = "Housewarming",
                    icon = "🏠",
                    color = "sky",
                    sortOrder = 7,
                    kind = EventTypeKind.MARKER,
                    createdAt = Instant.parse("2026-08-25T10:00:00Z"),
                    updatedAt = Instant.parse("2026-08-25T10:00:00Z"),
                )
            db.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "event_types",
                    entityId = preset.id,
                    operation = "upsert",
                    payloadJson = testJson.encodeToString(EventType.serializer(), preset),
                    createdAt = FIXED_NOW,
                ),
            )

            syncEngine(db, remote, notifier).runSync()

            val (table, row) = remote.upserts.single()
            assertThat(table).isEqualTo("event_types")
            assertThat(row["label"]?.toString()).isEqualTo("\"Housewarming\"")
            assertThat(row["sort_order"]?.toString()).isEqualTo("7")
            assertThat(row["color"]?.toString()).isEqualTo("\"sky\"")
            // ADR-041: the wire carries the lowercase kind value ("MARKER" locally).
            assertThat(row["kind"]?.toString()).isEqualTo("\"marker\"")
        }

    @Test
    fun `a rejected event_types pull does not abort the other tables`() =
        runTest {
            seedBusiness()
            // Shared migration 006 not applied: PostgREST rejects the table's pull.
            remote.onPull = { table, _ ->
                if (table == "event_types") throw RemoteRejectedException("relation event_types does not exist")
            }
            remote.servePage(
                "bookings",
                listOf(
                    buildJsonObject {
                        put("id", "b-after")
                        put("business_id", Fixtures.BUSINESS_ID)
                        put("event_type", "wedding")
                        put("event_icon", "💒")
                        put("customer_name", "remote-customer")
                        put("start_date", "2026-09-10")
                        put("end_date", "2026-09-10")
                        put("total_amount", "2000.00")
                        put("security_deposit", "0.00")
                        put("status", "confirmed")
                        put("created_by", Fixtures.USER_ID)
                        put("created_at", "2026-08-25T10:00:00+00:00")
                        put("updated_at", "2026-08-25T10:00:00+00:00")
                    },
                ),
            )

            val outcome = syncEngine(db, remote, notifier).runSync()

            // The bookings pull (registered AFTER event_types in SyncTables) still applied.
            assertThat(db.bookingDao().byId("b-after")).isNotNull()
            assertThat(outcome.networkFailed).isFalse()
            assertThat(db.eventTypeDao().presetsForBusinessOnce(Fixtures.BUSINESS_ID)).isEmpty()
        }
}
