package com.itsluminous.samaroh.core.invoice

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InvoiceNumberAllocatorTest {
    private lateinit var harness: InvoiceTestHarness

    @Before
    fun setUp() {
        harness = InvoiceTestHarness(invoiceTestDatabase())
    }

    @After
    fun tearDown() {
        harness.db.close()
    }

    @Test
    fun `allocates prefix-year-counter with zero padding`() =
        runTest {
            harness.seedBusinessAndBooking(bookingId = "booking-1")

            val number = harness.allocator.allocate("booking-1")

            assertThat(number).isEqualTo("INV-2026-0001")
        }

    @Test
    fun `allocation is idempotent - repeated calls return the frozen number`() =
        runTest {
            harness.seedBusinessAndBooking(bookingId = "booking-1")

            val first = harness.allocator.allocate("booking-1")
            val enqueuedAfterFirst = harness.outboxWriter.entries.size
            val second = harness.allocator.allocate("booking-1")

            assertThat(second).isEqualTo(first)
            // No counter consumed, nothing re-queued.
            assertThat(harness.businessRepository.business(Fixtures.BUSINESS_ID)!!.invoiceCounter).isEqualTo(1)
            assertThat(harness.outboxWriter.entries.size).isEqualTo(enqueuedAfterFirst)
            assertThat(harness.bookingRepository.booking("booking-1")!!.invoiceNumber).isEqualTo(first)
        }

    @Test
    fun `sequential bookings consume sequential counters`() =
        runTest {
            harness.seedBusinessAndBooking(bookingId = "booking-1")
            harness.bookingRepository.saveBooking(Fixtures.booking(id = "booking-2"))

            assertThat(harness.allocator.allocate("booking-1")).isEqualTo("INV-2026-0001")
            assertThat(harness.allocator.allocate("booking-2")).isEqualTo("INV-2026-0002")
        }

    @Test
    fun `allocation queues both rows on the outbox in the same logical step`() =
        runTest {
            harness.seedBusinessAndBooking(bookingId = "booking-1")
            harness.outboxWriter.entries.clear()

            harness.allocator.allocate("booking-1")

            assertThat(harness.outboxWriter.entries)
                .containsExactly(
                    RecordingOutboxWriter.Entry("businesses", Fixtures.BUSINESS_ID, OutboxOperation.UPSERT),
                    RecordingOutboxWriter.Entry("bookings", "booking-1", OutboxOperation.UPSERT),
                ).inOrder()
        }

    @Test
    fun `unknown booking fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { harness.allocator.allocate("missing") }
        }
    }
}
