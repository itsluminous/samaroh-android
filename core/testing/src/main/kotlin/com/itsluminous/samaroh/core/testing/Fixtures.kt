package com.itsluminous.samaroh.core.testing

import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.MasterItem
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.core.model.PaymentMethod
import com.itsluminous.samaroh.core.model.TxnType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Fixture builders for domain models: every field has a sensible default so tests
 * override only what they assert on. Names are intentionally not user-visible strings —
 * fixtures never reach the UI.
 */
object Fixtures {
    const val BUSINESS_ID = "00000000-0000-0000-0000-00000000b1a5"
    const val USER_ID = "00000000-0000-0000-0000-0000000000fe"
    val NOW: Instant = Instant.parse("2026-08-25T09:00:00Z")

    fun business(
        id: String = BUSINESS_ID,
        name: String = "fixture-business",
    ): Business =
        Business(
            id = id,
            name = name,
            ownerName = "fixture-owner",
            ownerUserId = USER_ID,
            createdAt = NOW,
            updatedAt = NOW,
        )

    fun booking(
        id: String = UUID.randomUUID().toString(),
        businessId: String = BUSINESS_ID,
        startDate: LocalDate = LocalDate.of(2026, 9, 10),
        endDate: LocalDate = startDate,
        status: BookingStatus = BookingStatus.CONFIRMED,
        totalAmountPaise: Long = 2_00_000_00L,
        securityDepositPaise: Long = 0L,
        eventType: String = "wedding",
    ): Booking =
        Booking(
            id = id,
            businessId = businessId,
            eventType = eventType,
            eventIcon = "\uD83D\uDC92",
            customerName = "fixture-customer",
            startDate = startDate,
            endDate = endDate,
            totalAmountPaise = totalAmountPaise,
            securityDepositPaise = securityDepositPaise,
            status = status,
            createdBy = USER_ID,
            createdAt = NOW,
            updatedAt = NOW,
        )

    fun payment(
        bookingId: String,
        id: String = UUID.randomUUID().toString(),
        amountPaise: Long = 50_000_00L,
        paidOn: LocalDate = LocalDate.of(2026, 8, 25),
        method: PaymentMethod = PaymentMethod.CASH,
    ): BookingPayment =
        BookingPayment(
            id = id,
            bookingId = bookingId,
            businessId = BUSINESS_ID,
            amountPaise = amountPaise,
            paidOn = paidOn,
            method = method,
            createdBy = USER_ID,
            createdAt = NOW,
            updatedAt = NOW,
        )

    fun party(
        id: String = UUID.randomUUID().toString(),
        name: String = "fixture-party",
    ): Party =
        Party(
            id = id,
            businessId = BUSINESS_ID,
            name = name,
            createdAt = NOW,
            updatedAt = NOW,
        )

    fun expense(
        partyId: String,
        id: String = UUID.randomUUID().toString(),
        direction: ExpenseDirection = ExpenseDirection.PAID,
        amountPaise: Long = 500_00L,
        expenseDate: LocalDate = LocalDate.of(2026, 8, 25),
    ): Expense =
        Expense(
            id = id,
            businessId = BUSINESS_ID,
            partyId = partyId,
            direction = direction,
            amountPaise = amountPaise,
            expenseDate = expenseDate,
            createdBy = USER_ID,
            createdAt = NOW,
            updatedAt = NOW,
        )

    fun masterItem(
        id: String = UUID.randomUUID().toString(),
        name: String = "fixture-item",
        unit: String = "pcs",
    ): MasterItem =
        MasterItem(
            id = id,
            businessId = BUSINESS_ID,
            name = name,
            unit = unit,
            createdAt = NOW,
            updatedAt = NOW,
        )

    fun inventoryTxn(
        masterItemId: String,
        id: String = UUID.randomUUID().toString(),
        type: TxnType = TxnType.ADD,
        quantity: Double = 10.0,
        unitPricePaise: Long = 100_00L,
        remainingQuantity: Double = if (type == TxnType.ADD) quantity else 0.0,
        transactionDate: Instant = NOW,
    ): InventoryTransaction =
        InventoryTransaction(
            id = id,
            businessId = BUSINESS_ID,
            masterItemId = masterItemId,
            transactionType = type,
            quantity = quantity,
            unitPricePaise = unitPricePaise,
            remainingQuantity = remainingQuantity,
            transactionDate = transactionDate,
            createdBy = USER_ID,
            createdAt = NOW,
            updatedAt = NOW,
        )
}
