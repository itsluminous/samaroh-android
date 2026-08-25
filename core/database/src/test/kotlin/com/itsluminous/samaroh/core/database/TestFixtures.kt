package com.itsluminous.samaroh.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.itsluminous.samaroh.core.database.entity.BookingEntity
import com.itsluminous.samaroh.core.database.entity.BookingPaymentEntity
import com.itsluminous.samaroh.core.database.entity.InventoryTransactionEntity
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.PaymentMethod
import com.itsluminous.samaroh.core.model.TxnType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** In-memory database for Robolectric DAO tests. */
fun testDatabase(): SamarohDatabase =
    Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), SamarohDatabase::class.java)
        .allowMainThreadQueries()
        .build()

const val TEST_BUSINESS_ID = "00000000-0000-0000-0000-00000000b1a5"
const val TEST_USER_ID = "00000000-0000-0000-0000-0000000000fe"

fun bookingFixture(
    id: String = UUID.randomUUID().toString(),
    businessId: String = TEST_BUSINESS_ID,
    startDate: LocalDate = LocalDate.of(2026, 9, 10),
    endDate: LocalDate = startDate,
    status: BookingStatus = BookingStatus.CONFIRMED,
    totalAmountPaise: Long = 2_00_000_00L,
    createdAt: Instant = Instant.parse("2026-08-01T09:00:00Z"),
): BookingEntity =
    BookingEntity(
        id = id,
        businessId = businessId,
        eventType = "wedding",
        eventIcon = "\uD83D\uDC92",
        customerName = "Customer $id",
        startDate = startDate,
        endDate = endDate,
        totalAmountPaise = totalAmountPaise,
        status = status,
        createdBy = TEST_USER_ID,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

fun paymentFixture(
    bookingId: String,
    amountPaise: Long = 50_000_00L,
    paidOn: LocalDate = LocalDate.of(2026, 8, 15),
    createdAt: Instant = Instant.parse("2026-08-15T09:00:00Z"),
): BookingPaymentEntity =
    BookingPaymentEntity(
        id = UUID.randomUUID().toString(),
        bookingId = bookingId,
        businessId = TEST_BUSINESS_ID,
        amountPaise = amountPaise,
        paidOn = paidOn,
        method = PaymentMethod.CASH,
        createdBy = TEST_USER_ID,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

fun inventoryTxnFixture(
    masterItemId: String,
    type: TxnType = TxnType.ADD,
    quantity: Double = 10.0,
    unitPricePaise: Long = 100_00L,
    remainingQuantity: Double = if (type == TxnType.ADD) quantity else 0.0,
    transactionDate: Instant = Instant.parse("2026-08-01T09:00:00Z"),
): InventoryTransactionEntity =
    InventoryTransactionEntity(
        id = UUID.randomUUID().toString(),
        businessId = TEST_BUSINESS_ID,
        masterItemId = masterItemId,
        transactionType = type,
        quantity = quantity,
        unitPricePaise = unitPricePaise,
        remainingQuantity = remainingQuantity,
        transactionDate = transactionDate,
        createdBy = TEST_USER_ID,
        createdAt = transactionDate,
        updatedAt = transactionDate,
    )

fun outboxFixture(
    entityType: String = "bookings",
    entityId: String = UUID.randomUUID().toString(),
    operation: String = "upsert",
    createdAt: Instant = Instant.parse("2026-08-01T09:00:00Z"),
): OutboxEntity =
    OutboxEntity(
        entityType = entityType,
        entityId = entityId,
        operation = operation,
        payloadJson = """{"id":"$entityId"}""",
        createdAt = createdAt,
    )
