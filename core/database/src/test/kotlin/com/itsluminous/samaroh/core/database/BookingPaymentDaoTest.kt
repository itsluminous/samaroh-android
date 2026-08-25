package com.itsluminous.samaroh.core.database

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.dao.BookingPaymentDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class BookingPaymentDaoTest {
    private lateinit var db: SamarohDatabase
    private lateinit var dao: BookingPaymentDao

    @Before
    fun setUp() {
        db = testDatabase()
        dao = db.bookingPaymentDao()
        runTest { db.bookingDao().upsert(bookingFixture(id = "b-1", totalAmountPaise = 2_00_000_00L)) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `payments for booking are ordered by paid_on`() =
        runTest {
            dao.upsert(paymentFixture("b-1", amountPaise = 25_000_00L, paidOn = LocalDate.of(2026, 9, 1)))
            dao.upsert(paymentFixture("b-1", amountPaise = 50_000_00L, paidOn = LocalDate.of(2026, 8, 15)))

            val payments = dao.paymentsForBooking("b-1").first()

            assertThat(payments.map { it.amountPaise }).containsExactly(50_000_00L, 25_000_00L).inOrder()
        }

    @Test
    fun `totalPaid sums live payments so due is computable`() =
        runTest {
            dao.upsert(paymentFixture("b-1", amountPaise = 50_000_00L))
            dao.upsert(paymentFixture("b-1", amountPaise = 25_000_00L, paidOn = LocalDate.of(2026, 9, 1)))

            val paid = dao.totalPaidPaise("b-1")
            assertThat(paid).isEqualTo(75_000_00L)

            // due = total − paid, always computed, never stored (§2).
            val due = db.bookingDao().byId("b-1")!!.totalAmountPaise - paid
            assertThat(due).isEqualTo(1_25_000_00L)
        }

    @Test
    fun `tombstoned payment is excluded from list and sum`() =
        runTest {
            val payment = paymentFixture("b-1", amountPaise = 50_000_00L)
            dao.upsert(payment)
            dao.upsert(paymentFixture("b-1", amountPaise = 25_000_00L, paidOn = LocalDate.of(2026, 9, 1)))

            dao.tombstone(payment.id, Instant.parse("2026-09-02T10:00:00Z"))

            assertThat(dao.totalPaidPaise("b-1")).isEqualTo(25_000_00L)
            assertThat(dao.paymentsForBooking("b-1").first()).hasSize(1)
        }

    @Test
    fun `payments of other bookings are not included`() =
        runTest {
            db.bookingDao().upsert(bookingFixture(id = "b-2"))
            dao.upsert(paymentFixture("b-1", amountPaise = 10_000_00L))
            dao.upsert(paymentFixture("b-2", amountPaise = 99_000_00L))

            assertThat(dao.totalPaidPaise("b-1")).isEqualTo(10_000_00L)
        }
}
