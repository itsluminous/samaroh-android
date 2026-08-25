package com.itsluminous.samaroh.core.invoice

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.itsluminous.samaroh.core.data.repository.RoomBookingRepository
import com.itsluminous.samaroh.core.data.repository.RoomBusinessRepository
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/*
 * Shared helpers for core:invoice tests.
 */

val INVOICE_TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-25T09:00:00Z"), ZoneOffset.UTC)

fun invoiceTestDatabase(): SamarohDatabase = inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())

/** A context whose resources render in [languageTag] — the per-app-locale test stand-in. */
fun localizedContext(languageTag: String): Context {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(languageTag)) }
    return base.createConfigurationContext(config)
}

class RecordingOutboxWriter : OutboxWriter {
    data class Entry(
        val entityType: String,
        val entityId: String,
        val operation: OutboxOperation,
    )

    val entries = mutableListOf<Entry>()

    override suspend fun enqueue(
        entityType: String,
        entityId: String,
        operation: OutboxOperation,
        payloadJson: String,
    ) {
        entries += Entry(entityType, entityId, operation)
    }
}

class InvoiceTestHarness(
    val db: SamarohDatabase,
) {
    val outboxWriter = RecordingOutboxWriter()
    val bookingRepository =
        RoomBookingRepository(
            bookingDao = db.bookingDao(),
            paymentDao = db.bookingPaymentDao(),
            dateBlockDao = db.dateBlockDao(),
            outboxWriter = outboxWriter,
            clock = INVOICE_TEST_CLOCK,
        )
    val businessRepository =
        RoomBusinessRepository(
            businessDao = db.businessDao(),
            settingsDao = db.businessSettingsDao(),
            outboxWriter = outboxWriter,
        )
    val allocator = RoomInvoiceNumberAllocator(bookingRepository, businessRepository, INVOICE_TEST_CLOCK)
    val loader = InvoiceDataLoader(bookingRepository, businessRepository, allocator, INVOICE_TEST_CLOCK)

    suspend fun seedBusinessAndBooking(
        bookingId: String = "booking-1",
        totalAmountPaise: Long = 2_00_000_00L,
        securityDepositPaise: Long = 0L,
        advancePaise: Long? = null,
    ) {
        businessRepository.saveBusiness(Fixtures.business())
        bookingRepository.saveBooking(
            Fixtures.booking(
                id = bookingId,
                totalAmountPaise = totalAmountPaise,
                securityDepositPaise = securityDepositPaise,
            ),
        )
        if (advancePaise != null) {
            bookingRepository.recordPayment(Fixtures.payment(bookingId, amountPaise = advancePaise))
        }
    }
}

/** In-memory [InvoiceData] for renderer/text tests that don't need a database. */
fun invoiceData(
    totalAmountPaise: Long = 2_00_000_00L,
    securityDepositPaise: Long = 50_000_00L,
    paymentAmountsPaise: List<Long> = listOf(50_000_00L),
    invoiceNumber: String = "INV-2026-0001",
): InvoiceData {
    val booking =
        Fixtures.booking(
            id = "booking-1",
            totalAmountPaise = totalAmountPaise,
            securityDepositPaise = securityDepositPaise,
        )
    return InvoiceData(
        business = Fixtures.business(),
        booking = booking,
        payments = paymentAmountsPaise.map { Fixtures.payment(booking.id, amountPaise = it) },
        invoiceNumber = invoiceNumber,
        issueDate = LocalDate.of(2026, 8, 25),
    )
}
