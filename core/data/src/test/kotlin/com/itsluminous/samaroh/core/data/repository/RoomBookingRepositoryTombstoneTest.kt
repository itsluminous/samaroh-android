package com.itsluminous.samaroh.core.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Permanent booking delete (ADR-054): the tombstone lands in Room (`deleted_at`) — the
 * row is soft-deleted, never removed — AND the outbox carries a DELETE push whose
 * payload releases the booking on every other device. A restore-style save enqueues a
 * plain UPSERT (the calendar trigger's cue, ADR-046).
 */
@RunWith(RobolectricTestRunner::class)
class RoomBookingRepositoryTombstoneTest {
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

    private val now: Instant = Instant.parse("2026-09-07T06:00:00Z")
    private lateinit var db: SamarohDatabase
    private lateinit var outbox: RecordingOutboxWriter
    private lateinit var repository: RoomBookingRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        outbox = RecordingOutboxWriter()
        repository =
            RoomBookingRepository(
                bookingDao = db.bookingDao(),
                paymentDao = db.bookingPaymentDao(),
                dateBlockDao = db.dateBlockDao(),
                reminderDao = db.paymentReminderDao(),
                outboxWriter = outbox,
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `deleteBooking tombstones the row and enqueues a DELETE outbox push`() =
        runTest {
            val booking = Fixtures.booking(status = BookingStatus.CANCELLED)
            repository.saveBooking(booking)
            outbox.records.clear()

            repository.deleteBooking(booking.id)

            // Soft delete: the row still exists, carrying deleted_at (§8).
            val stored = repository.booking(booking.id)
            assertThat(stored).isNotNull()
            assertThat(stored!!.deletedAt).isEqualTo(now)

            // Released from the calendar window query.
            val visible =
                repository
                    .bookingsBetween(booking.businessId, booking.startDate.minusDays(1), booking.endDate.plusDays(1))
                    .first()
            assertThat(visible).isEmpty()

            val record = outbox.records.single()
            assertThat(record.entityType).isEqualTo("bookings")
            assertThat(record.entityId).isEqualTo(booking.id)
            assertThat(record.operation).isEqualTo(OutboxOperation.DELETE)
            val payload = Json.parseToJsonElement(record.payloadJson).jsonObject
            assertThat(payload["id"]?.jsonPrimitive?.content).isEqualTo(booking.id)
            assertThat(payload["deleted_at"]?.jsonPrimitive?.content).isEqualTo(now.toString())
        }

    @Test
    fun `restore save enqueues an UPSERT carrying the confirmed status`() =
        runTest {
            val cancelled = Fixtures.booking(startDate = LocalDate.of(2026, 9, 20), status = BookingStatus.CANCELLED)
            repository.saveBooking(cancelled)
            outbox.records.clear()

            repository.saveBooking(cancelled.copy(status = BookingStatus.CONFIRMED, updatedAt = now))

            assertThat(repository.booking(cancelled.id)!!.status).isEqualTo(BookingStatus.CONFIRMED)
            val record = outbox.records.single()
            assertThat(record.operation).isEqualTo(OutboxOperation.UPSERT)
            val payload = Json.parseToJsonElement(record.payloadJson).jsonObject
            assertThat(payload["status"]?.jsonPrimitive?.content).ignoringCase().isEqualTo("confirmed")
        }
}
