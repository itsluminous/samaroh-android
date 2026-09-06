package com.itsluminous.samaroh.core.google.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

private const val BIZ = Fixtures.BUSINESS_ID

/**
 * REQ "immediate sync" (ADR-046): a booking/payment outbox mutation of a
 * gcal-enabled business must enqueue the debounced calendar one-shot; anything else
 * must not.
 */
@RunWith(RobolectricTestRunner::class)
class BookingMutationCalendarTriggerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class FakeBusinessRepository(
        private val enabled: Boolean,
    ) : BusinessRepository {
        override fun businesses(): Flow<List<Business>> = flowOf(emptyList())

        override suspend fun business(id: String): Business? = null

        override suspend fun saveBusiness(business: Business) = error("unused")

        override fun settings(businessId: String): Flow<BusinessSettings?> =
            flowOf(BusinessSettings(businessId = businessId, gcalSyncEnabled = enabled, updatedAt = Instant.EPOCH))

        override suspend fun saveSettings(settings: BusinessSettings) = error("unused")
    }

    private class FakeBookingRepository(
        private val bookings: Map<String, Booking>,
    ) : BookingRepository {
        override fun bookingsBetween(
            businessId: String,
            from: LocalDate,
            to: LocalDate,
        ): Flow<List<Booking>> = flowOf(emptyList())

        override suspend fun bookingDateBounds(businessId: String): ClosedRange<LocalDate>? = null

        override suspend fun booking(id: String): Booking? = bookings[id]

        override suspend fun saveBooking(booking: Booking) = error("unused")

        override suspend fun deleteBooking(id: String) = error("unused")

        override suspend fun countBookingsOn(
            businessId: String,
            date: LocalDate,
        ): Int = 0

        override fun paymentsForBooking(bookingId: String): Flow<List<BookingPayment>> = flowOf(emptyList())

        override fun paymentsForBookings(bookingIds: List<String>): Flow<List<BookingPayment>> = flowOf(emptyList())

        override suspend fun recordPayment(payment: BookingPayment) = error("unused")

        override suspend fun totalPaidPaise(bookingId: String): Long = 0

        override fun dateBlocksBetween(
            businessId: String,
            from: LocalDate,
            to: LocalDate,
        ): Flow<List<DateBlock>> = flowOf(emptyList())

        override suspend fun saveDateBlock(block: DateBlock) = error("unused")

        override suspend fun deleteDateBlock(id: String) = error("unused")

        override fun duePendingReminders(
            businessId: String,
            onOrBefore: LocalDate,
        ): Flow<List<PaymentReminder>> = flowOf(emptyList())

        override suspend fun duePendingRemindersOnce(
            businessId: String,
            onOrBefore: LocalDate,
        ): List<PaymentReminder> = emptyList()

        override suspend fun remindersForBooking(bookingId: String): List<PaymentReminder> = emptyList()

        override suspend fun reminder(id: String): PaymentReminder? = null

        override suspend fun saveReminder(reminder: PaymentReminder) = error("unused")

        override suspend fun bookingsEndedBefore(
            businessId: String,
            date: LocalDate,
        ): List<Booking> = emptyList()

        override suspend fun bookingsStartingOn(
            businessId: String,
            date: LocalDate,
        ): List<Booking> = emptyList()

        override suspend fun invoiceNumberExists(
            businessId: String,
            invoiceNumber: String,
            excludingBookingId: String?,
        ): Boolean = false
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun pendingOnChangeWork(): Int =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(CalendarSyncWorker.onChangeWorkName(BIZ))
            .get()
            .count { !it.state.isFinished }

    private fun trigger(
        enabled: Boolean = true,
        bookings: Map<String, Booking> = emptyMap(),
    ) = BookingMutationCalendarTrigger(
        bookingRepository = FakeBookingRepository(bookings),
        businessRepository = FakeBusinessRepository(enabled),
        scheduler = CalendarSyncScheduler(context),
    )

    @Test
    fun `booking upsert of a gcal-enabled business enqueues the calendar push`() =
        runTest {
            trigger().onLocalMutation("bookings", "b-1", OutboxOperation.UPSERT, """{"business_id":"$BIZ"}""")
            assertThat(pendingOnChangeWork()).isEqualTo(1)
        }

    @Test
    fun `payment upsert also enqueues - amounts are part of the event description`() =
        runTest {
            trigger().onLocalMutation("booking_payments", "p-1", OutboxOperation.UPSERT, """{"business_id":"$BIZ"}""")
            assertThat(pendingOnChangeWork()).isEqualTo(1)
        }

    @Test
    fun `booking tombstone resolves the business through the Room row`() =
        runTest {
            val booking = Fixtures.booking(id = "b-1")
            trigger(bookings = mapOf("b-1" to booking))
                .onLocalMutation("bookings", "b-1", OutboxOperation.DELETE, """{"id":"b-1"}""")
            assertThat(pendingOnChangeWork()).isEqualTo(1)
        }

    @Test
    fun `nothing is enqueued when calendar sync is disabled`() =
        runTest {
            trigger(enabled = false).onLocalMutation("bookings", "b-1", OutboxOperation.UPSERT, """{"business_id":"$BIZ"}""")
            assertThat(pendingOnChangeWork()).isEqualTo(0)
        }

    @Test
    fun `non-booking mutations never enqueue calendar work`() =
        runTest {
            trigger().onLocalMutation("expenses", "e-1", OutboxOperation.UPSERT, """{"business_id":"$BIZ"}""")
            trigger().onLocalMutation("business_settings", BIZ, OutboxOperation.UPSERT, """{"business_id":"$BIZ"}""")
            assertThat(pendingOnChangeWork()).isEqualTo(0)
        }
}
