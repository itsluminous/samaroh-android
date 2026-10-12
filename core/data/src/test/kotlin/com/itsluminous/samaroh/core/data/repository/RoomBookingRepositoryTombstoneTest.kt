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

    // ---- ADR-105: delete payment ----

    @Test
    fun `deletePayment tombstones the row, drops it from every live read and enqueues a DELETE push`() =
        runTest {
            val booking = Fixtures.booking(startDate = LocalDate.of(2026, 9, 10))
            repository.saveBooking(booking)
            val keep = Fixtures.payment(booking.id, id = "p-keep", amountPaise = 50_000_00L, paidOn = LocalDate.of(2026, 9, 1))
            val wrong = Fixtures.payment(booking.id, id = "p-wrong", amountPaise = 1_50_000_00L, paidOn = LocalDate.of(2026, 9, 2))
            repository.recordPayment(keep)
            repository.recordPayment(wrong)
            assertThat(repository.totalPaidPaise(booking.id)).isEqualTo(2_00_000_00L)
            outbox.records.clear()

            repository.deletePayment(wrong.id)

            // Due is never stored: every reader recomputes from the live rows only.
            assertThat(repository.totalPaidPaise(booking.id)).isEqualTo(50_000_00L)
            assertThat(repository.paymentsForBooking(booking.id).first().map { it.id }).containsExactly("p-keep")
            assertThat(repository.paymentsForBookings(listOf(booking.id)).first().map { it.id }).containsExactly("p-keep")
            // Soft delete: the row survives as a tombstone (LWW against other devices).
            val tombstoned = db.bookingPaymentDao().byId(wrong.id)
            assertThat(tombstoned).isNotNull()
            assertThat(tombstoned!!.deletedAt).isEqualTo(now)
            assertThat(tombstoned.updatedAt).isEqualTo(now)

            // Sync op shape: DELETE on booking_payments → server UPDATE deleted_at (never a
            // row delete); the payload also names the business so the Google Calendar
            // trigger can scope the re-sync without a Room lookup.
            val record = outbox.records.single()
            assertThat(record.entityType).isEqualTo("booking_payments")
            assertThat(record.entityId).isEqualTo(wrong.id)
            assertThat(record.operation).isEqualTo(OutboxOperation.DELETE)
            val payload = Json.parseToJsonElement(record.payloadJson).jsonObject
            assertThat(payload.keys).containsExactly("id", "deleted_at", "business_id")
            assertThat(payload["id"]?.jsonPrimitive?.content).isEqualTo(wrong.id)
            assertThat(payload["deleted_at"]?.jsonPrimitive?.content).isEqualTo(now.toString())
            assertThat(payload["business_id"]?.jsonPrimitive?.content).isEqualTo(booking.businessId)
        }

    @Test
    fun `deletePayment is idempotent and ignores unknown ids`() =
        runTest {
            val booking = Fixtures.booking(startDate = LocalDate.of(2026, 9, 10))
            repository.saveBooking(booking)
            val payment = Fixtures.payment(booking.id, id = "p1")
            repository.recordPayment(payment)
            outbox.records.clear()

            repository.deletePayment(payment.id)
            repository.deletePayment(payment.id) // second tap / replayed intent
            repository.deletePayment("never-existed")

            assertThat(outbox.records).hasSize(1)
            assertThat(outbox.records.single().operation).isEqualTo(OutboxOperation.DELETE)
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
