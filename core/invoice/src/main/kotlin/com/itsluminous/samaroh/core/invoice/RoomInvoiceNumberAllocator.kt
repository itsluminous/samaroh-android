package com.itsluminous.samaroh.core.invoice

import com.itsluminous.samaroh.core.data.invoice.InvoiceNumberAllocator
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assigns `{prefix}-{YYYY}-{counter:04d}` invoice numbers (spec §4.1,
 * shared/invoice/layout-spec.md):
 * - prefix = `businesses.invoice_prefix`, YYYY = year of FIRST generation,
 *   counter = `businesses.invoice_counter` incremented atomically;
 * - assigned exactly once per booking, immutable afterwards (`bookings.invoice_number`);
 * - idempotent: an already-numbered booking returns its frozen number without consuming
 *   a counter value;
 * - the counter bump and the booking freeze both go through the repositories, so each
 *   lands in Room AND the outbox in the same logical step (ADR-006).
 */
@Singleton
class RoomInvoiceNumberAllocator
    @Inject
    constructor(
        private val bookingRepository: BookingRepository,
        private val businessRepository: BusinessRepository,
        private val clock: Clock,
    ) : InvoiceNumberAllocator {
        private val mutex = Mutex()

        override suspend fun allocate(bookingId: String): String =
            mutex.withLock {
                val booking = requireNotNull(bookingRepository.booking(bookingId)) { "unknown booking: $bookingId" }
                booking.invoiceNumber?.let { return@withLock it }
                val business =
                    requireNotNull(businessRepository.business(booking.businessId)) {
                        "unknown business: ${booking.businessId}"
                    }
                val counter = business.invoiceCounter + 1
                val year = LocalDate.now(clock).year
                val number = "${business.invoicePrefix}-$year-${counter.toString().padStart(COUNTER_DIGITS, '0')}"
                val now = clock.instant()
                businessRepository.saveBusiness(business.copy(invoiceCounter = counter, updatedAt = now))
                bookingRepository.saveBooking(booking.copy(invoiceNumber = number, updatedAt = now))
                number
            }

        private companion object {
            const val COUNTER_DIGITS = 4
        }
    }
