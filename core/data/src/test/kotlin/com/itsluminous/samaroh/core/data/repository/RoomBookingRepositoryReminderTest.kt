package com.itsluminous.samaroh.core.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
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
 * Payment-reminder rows are DEVICE-LOCAL (ADR-095): [RoomBookingRepository.saveReminder]
 * writes Room only — never the outbox. This is what keeps a viewer device (no
 * `booking.record_payment`) from queueing a push RLS will reject forever, and what gives
 * two devices of the same user independent reminder state.
 */
@RunWith(RobolectricTestRunner::class)
class RoomBookingRepositoryReminderTest {
    private class RecordingOutboxWriter : OutboxWriter {
        val entityTypes = mutableListOf<String>()

        override suspend fun enqueue(
            entityType: String,
            entityId: String,
            operation: OutboxOperation,
            payloadJson: String,
        ) {
            entityTypes += entityType
        }
    }

    private val now: Instant = Instant.parse("2026-10-07T06:00:00Z")
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
    fun `saveReminder persists locally and never touches the outbox`() =
        runTest {
            val reminder =
                PaymentReminder(
                    id = "r-1",
                    bookingId = "b-1",
                    businessId = Fixtures.BUSINESS_ID,
                    remindOn = LocalDate.of(2026, 10, 7),
                    status = ReminderStatus.PENDING,
                    amountDueSnapshotPaise = 50_000L,
                    createdAt = now,
                    updatedAt = now,
                )

            repository.saveReminder(reminder)
            repository.saveReminder(reminder.copy(status = ReminderStatus.DISMISSED))

            assertThat(repository.reminder("r-1")?.status).isEqualTo(ReminderStatus.DISMISSED)
            assertThat(outbox.entityTypes).isEmpty()
        }
}
