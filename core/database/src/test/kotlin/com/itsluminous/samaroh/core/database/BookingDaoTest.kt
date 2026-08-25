package com.itsluminous.samaroh.core.database

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.dao.BookingDao
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
class BookingDaoTest {
    private lateinit var db: SamarohDatabase
    private lateinit var dao: BookingDao

    @Before
    fun setUp() {
        db = testDatabase()
        dao = db.bookingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and read back by id`() =
        runTest {
            val booking = bookingFixture(id = "b-1")
            dao.upsert(booking)
            assertThat(dao.byId("b-1")).isEqualTo(booking)
        }

    @Test
    fun `date range query returns overlapping bookings only`() =
        runTest {
            val inRange = bookingFixture(id = "in", startDate = LocalDate.of(2026, 9, 10))
            val spanning =
                bookingFixture(
                    id = "spanning",
                    startDate = LocalDate.of(2026, 8, 30),
                    endDate = LocalDate.of(2026, 9, 2),
                )
            val before = bookingFixture(id = "before", startDate = LocalDate.of(2026, 8, 20))
            val after = bookingFixture(id = "after", startDate = LocalDate.of(2026, 10, 1))
            listOf(inRange, spanning, before, after).forEach { dao.upsert(it) }

            val september =
                dao
                    .bookingsBetween(TEST_BUSINESS_ID, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
                    .first()

            assertThat(september.map { it.id }).containsExactly("spanning", "in").inOrder()
        }

    @Test
    fun `date range query excludes other businesses`() =
        runTest {
            dao.upsert(bookingFixture(id = "mine"))
            dao.upsert(bookingFixture(id = "theirs", businessId = "other-business"))

            val result =
                dao
                    .bookingsBetween(TEST_BUSINESS_ID, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
                    .first()

            assertThat(result.map { it.id }).containsExactly("mine")
        }

    @Test
    fun `tombstoned booking disappears from queries but the row remains`() =
        runTest {
            dao.upsert(bookingFixture(id = "b-1"))
            val at = Instant.parse("2026-09-01T10:00:00Z")

            dao.tombstone("b-1", at)

            val visible =
                dao
                    .bookingsBetween(TEST_BUSINESS_ID, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
                    .first()
            assertThat(visible).isEmpty()

            // Soft delete: the row survives with deleted_at + bumped updated_at for sync.
            val raw = dao.byId("b-1")
            assertThat(raw).isNotNull()
            assertThat(raw!!.deletedAt).isEqualTo(at)
            assertThat(raw.updatedAt).isEqualTo(at)
        }

    @Test
    fun `countBookingsOn ignores cancelled bookings`() =
        runTest {
            val date = LocalDate.of(2026, 9, 10)
            dao.upsert(bookingFixture(id = "confirmed", startDate = date))
            dao.upsert(
                bookingFixture(
                    id = "cancelled",
                    startDate = date,
                    status = com.itsluminous.samaroh.core.model.BookingStatus.CANCELLED,
                ),
            )

            assertThat(dao.countBookingsOn(TEST_BUSINESS_ID, date)).isEqualTo(1)
        }
}
