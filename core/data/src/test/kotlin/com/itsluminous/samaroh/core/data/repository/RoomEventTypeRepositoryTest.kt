package com.itsluminous.samaroh.core.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
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
 * Event-type preset persistence (ADR-032): CRUD lands in Room AND the outbox, seeding
 * runs exactly once per business, and bookings keep their SNAPSHOTTED label/icon when a
 * preset is later renamed or deleted.
 */
@RunWith(RobolectricTestRunner::class)
class RoomEventTypeRepositoryTest {
    private data class OutboxRecord(
        val entityType: String,
        val entityId: String,
        val operation: OutboxOperation,
        val payloadJson: String,
    )

    private class RecordingOutboxWriter : OutboxWriter {
        val records = mutableListOf<OutboxRecord>()

        override suspend fun enqueue(
            entityType: String,
            entityId: String,
            operation: OutboxOperation,
            payloadJson: String,
        ) {
            records += OutboxRecord(entityType, entityId, operation, payloadJson)
        }
    }

    private class FixedSeedTemplate : EventTypeSeedTemplate {
        override fun seeds(): List<EventTypeSeed> =
            listOf(
                EventTypeSeed(label = "Wedding", icon = "💒", color = "tomato", sortOrder = 0),
                EventTypeSeed(label = "Birthday", icon = "🎂", color = "banana", sortOrder = 1),
                EventTypeSeed(label = "Custom", icon = "✨", color = "grape", sortOrder = 2),
            )
    }

    private val now: Instant = Instant.parse("2026-08-27T10:00:00Z")
    private lateinit var db: SamarohDatabase
    private lateinit var outbox: RecordingOutboxWriter
    private lateinit var repository: RoomEventTypeRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        outbox = RecordingOutboxWriter()
        repository =
            RoomEventTypeRepository(
                eventTypeDao = db.eventTypeDao(),
                outboxWriter = outbox,
                seedTemplate = FixedSeedTemplate(),
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun preset(
        label: String,
        sortOrder: Int = 0,
        id: String = "et-$label",
        color: String? = null,
    ) = EventType(
        id = id,
        businessId = Fixtures.BUSINESS_ID,
        label = label,
        icon = "💒",
        color = color,
        sortOrder = sortOrder,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `savePreset lands in room and enqueues an upsert with the full payload`() =
        runTest {
            repository.savePreset(preset("Wedding", color = "tomato"))

            val stored = repository.presetsOnce(Fixtures.BUSINESS_ID).single()
            assertThat(stored.label).isEqualTo("Wedding")
            assertThat(stored.color).isEqualTo("tomato")

            val record = outbox.records.single()
            assertThat(record.entityType).isEqualTo("event_types")
            assertThat(record.entityId).isEqualTo("et-Wedding")
            assertThat(record.operation).isEqualTo(OutboxOperation.UPSERT)
            assertThat(record.payloadJson).contains("\"label\":\"Wedding\"")
            assertThat(record.payloadJson).contains("\"sort_order\":0")
        }

    @Test
    fun `presets flow returns live rows in sort order and hides tombstones`() =
        runTest {
            repository.savePreset(preset("Zebra Party", sortOrder = 2))
            repository.savePreset(preset("Wedding", sortOrder = 0))
            repository.savePreset(preset("Birthday", sortOrder = 1))
            repository.deletePreset("et-Birthday")

            assertThat(repository.presets(Fixtures.BUSINESS_ID).first().map { it.label })
                .containsExactly("Wedding", "Zebra Party")
                .inOrder()
        }

    @Test
    fun `deletePreset tombstones and enqueues a delete push`() =
        runTest {
            repository.savePreset(preset("Wedding"))
            repository.deletePreset("et-Wedding")

            // Soft delete: the row survives with deleted_at set.
            assertThat(repository.preset("et-Wedding")?.deletedAt).isEqualTo(now)
            val delete = outbox.records.last()
            assertThat(delete.entityType).isEqualTo("event_types")
            assertThat(delete.operation).isEqualTo(OutboxOperation.DELETE)
            assertThat(delete.payloadJson).contains("\"deleted_at\"")
        }

    @Test
    fun `labelInUse is case-insensitive, skips tombstones and the row being edited`() =
        runTest {
            repository.savePreset(preset("Wedding"))
            repository.savePreset(preset("Haldi", id = "et-Haldi", sortOrder = 1))
            repository.deletePreset("et-Haldi")

            assertThat(repository.labelInUse(Fixtures.BUSINESS_ID, "wedding")).isTrue()
            assertThat(repository.labelInUse(Fixtures.BUSINESS_ID, " Wedding ")).isTrue()
            // A deleted preset's name is reusable (partial unique index semantics).
            assertThat(repository.labelInUse(Fixtures.BUSINESS_ID, "Haldi")).isFalse()
            // The row being edited never counts as its own duplicate.
            assertThat(repository.labelInUse(Fixtures.BUSINESS_ID, "Wedding", excludingId = "et-Wedding")).isFalse()
        }

    @Test
    fun `seedDefaults inserts the template once with outbox pushes`() =
        runTest {
            repository.seedDefaults(Fixtures.BUSINESS_ID)

            val seeded = repository.presetsOnce(Fixtures.BUSINESS_ID)
            assertThat(seeded.map { it.label }).containsExactly("Wedding", "Birthday", "Custom").inOrder()
            assertThat(seeded.map { it.color }).containsExactly("tomato", "banana", "grape").inOrder()
            assertThat(outbox.records).hasSize(3)
            assertThat(outbox.records.map { it.entityType }.toSet()).containsExactly("event_types")
        }

    @Test
    fun `seedDefaults never reseeds a business that already has rows`() =
        runTest {
            // A single pulled row (server migration 006 seeded this business) blocks seeding.
            repository.savePreset(preset("Wedding"))
            outbox.records.clear()

            repository.seedDefaults(Fixtures.BUSINESS_ID)

            assertThat(repository.presetsOnce(Fixtures.BUSINESS_ID)).hasSize(1)
            assertThat(outbox.records).isEmpty()
        }

    @Test
    fun `seedDefaults skips a business whose presets were all deleted`() =
        runTest {
            repository.savePreset(preset("Wedding"))
            repository.deletePreset("et-Wedding")

            repository.seedDefaults(Fixtures.BUSINESS_ID)

            // Tombstones count as "was seeded": the user's deletions are respected.
            assertThat(repository.presetsOnce(Fixtures.BUSINESS_ID)).isEmpty()
        }

    @Test
    fun `renaming a preset never rewrites bookings recorded from it - snapshot semantics`() =
        runTest {
            // The booking snapshotted the preset's label + icon at save time (ADR-032).
            repository.savePreset(preset("Wedding", color = "tomato"))
            val booking = Fixtures.booking().copy(eventType = "Wedding", eventIcon = "💒")
            db.bookingDao().upsert(booking.toEntity())

            val renamed =
                repository.preset("et-Wedding")!!.copy(label = "Shaadi", icon = "🎉", updatedAt = now.plusSeconds(60))
            repository.savePreset(renamed)
            repository.deletePreset("et-Wedding")

            val stored = db.bookingDao().byId(booking.id)!!
            assertThat(stored.eventType).isEqualTo("Wedding")
            assertThat(stored.eventIcon).isEqualTo("💒")
        }
}
