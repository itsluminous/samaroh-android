package com.itsluminous.samaroh.core.data.repository

import com.itsluminous.samaroh.core.database.entity.BookingEntity
import com.itsluminous.samaroh.core.database.entity.BookingPaymentEntity
import com.itsluminous.samaroh.core.database.entity.BusinessEntity
import com.itsluminous.samaroh.core.database.entity.BusinessMemberEntity
import com.itsluminous.samaroh.core.database.entity.BusinessSettingsEntity
import com.itsluminous.samaroh.core.database.entity.DateBlockEntity
import com.itsluminous.samaroh.core.database.entity.ExpenseEntity
import com.itsluminous.samaroh.core.database.entity.InventoryTransactionEntity
import com.itsluminous.samaroh.core.database.entity.MasterItemEntity
import com.itsluminous.samaroh.core.database.entity.PartyEntity
import com.itsluminous.samaroh.core.database.entity.PaymentReminderEntity
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.MasterItem
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.core.model.PaymentReminder

// Mechanical entity <-> model mapping. Field sets are identical by contract.

internal fun BookingEntity.toModel() =
    Booking(
        id,
        businessId,
        eventType,
        eventIcon,
        customerName,
        customerPhone,
        startDate,
        endDate,
        startTime,
        endTime,
        totalAmountPaise,
        securityDepositPaise,
        source,
        notes,
        status,
        gcalEventId,
        invoiceNumber,
        createdBy,
        updatedBy,
        createdAt,
        updatedAt,
        deletedAt,
    )

internal fun Booking.toEntity() =
    BookingEntity(
        id,
        businessId,
        eventType,
        eventIcon,
        customerName,
        customerPhone,
        startDate,
        endDate,
        startTime,
        endTime,
        totalAmountPaise,
        securityDepositPaise,
        source,
        notes,
        status,
        gcalEventId,
        invoiceNumber,
        createdBy,
        updatedBy,
        createdAt,
        updatedAt,
        deletedAt,
    )

internal fun DateBlockEntity.toModel() = DateBlock(id, businessId, startDate, endDate, reason, createdBy, createdAt, updatedAt, deletedAt)

internal fun DateBlock.toEntity() = DateBlockEntity(id, businessId, startDate, endDate, reason, createdBy, createdAt, updatedAt, deletedAt)

internal fun BookingPaymentEntity.toModel() =
    BookingPayment(id, bookingId, businessId, amountPaise, paidOn, method, notes, createdBy, createdAt, updatedAt, deletedAt)

internal fun BookingPayment.toEntity() =
    BookingPaymentEntity(id, bookingId, businessId, amountPaise, paidOn, method, notes, createdBy, createdAt, updatedAt, deletedAt)

internal fun PaymentReminderEntity.toModel() =
    PaymentReminder(id, bookingId, businessId, remindOn, status, amountDueSnapshotPaise, kind, createdAt, updatedAt, deletedAt)

internal fun PaymentReminder.toEntity() =
    PaymentReminderEntity(id, bookingId, businessId, remindOn, status, amountDueSnapshotPaise, kind, createdAt, updatedAt, deletedAt)

internal fun PartyEntity.toModel() = Party(id, businessId, name, phone, notes, createdAt, updatedAt, deletedAt)

internal fun Party.toEntity() = PartyEntity(id, businessId, name, phone, notes, createdAt, updatedAt, deletedAt)

internal fun ExpenseEntity.toModel() =
    Expense(id, businessId, partyId, direction, amountPaise, expenseDate, notes, createdBy, createdAt, updatedAt, deletedAt)

internal fun Expense.toEntity() =
    ExpenseEntity(id, businessId, partyId, direction, amountPaise, expenseDate, notes, createdBy, createdAt, updatedAt, deletedAt)

internal fun MasterItemEntity.toModel() = MasterItem(id, businessId, name, unit, imagePath, driveImageId, createdAt, updatedAt, deletedAt)

internal fun MasterItem.toEntity() = MasterItemEntity(id, businessId, name, unit, imagePath, driveImageId, createdAt, updatedAt, deletedAt)

internal fun InventoryTransactionEntity.toModel() =
    InventoryTransaction(
        id,
        businessId,
        masterItemId,
        transactionType,
        quantity,
        unitPricePaise,
        remainingQuantity,
        transactionDate,
        notes,
        createdBy,
        createdAt,
        updatedAt,
        deletedAt,
    )

internal fun InventoryTransaction.toEntity() =
    InventoryTransactionEntity(
        id,
        businessId,
        masterItemId,
        transactionType,
        quantity,
        unitPricePaise,
        remainingQuantity,
        transactionDate,
        notes,
        createdBy,
        createdAt,
        updatedAt,
        deletedAt,
    )

internal fun BusinessEntity.toModel() =
    Business(
        id,
        name,
        businessType,
        address,
        ownerName,
        logoPath,
        currency,
        invoicePrefix,
        invoiceCounter,
        ownerUserId,
        createdAt,
        updatedAt,
        deletedAt,
    )

internal fun Business.toEntity() =
    BusinessEntity(
        id,
        name,
        businessType,
        address,
        ownerName,
        logoPath,
        currency,
        invoicePrefix,
        invoiceCounter,
        ownerUserId,
        createdAt,
        updatedAt,
        deletedAt,
    )

internal fun BusinessMemberEntity.toModel() =
    BusinessMember(id, businessId, invitedEmail, userId, displayName, isOwner, status, permissions, createdAt, updatedAt, deletedAt)

internal fun BusinessMember.toEntity() =
    BusinessMemberEntity(id, businessId, invitedEmail, userId, displayName, isOwner, status, permissions, createdAt, updatedAt, deletedAt)

internal fun BusinessSettingsEntity.toModel() = BusinessSettings(businessId, gcalSyncEnabled, backupFrequency, lastBackupAt, updatedAt)

internal fun BusinessSettings.toEntity() = BusinessSettingsEntity(businessId, gcalSyncEnabled, backupFrequency, lastBackupAt, updatedAt)
