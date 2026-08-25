package com.itsluminous.samaroh.core.sync.engine

import com.itsluminous.samaroh.core.database.entity.BookingEntity
import com.itsluminous.samaroh.core.database.entity.BookingPaymentEntity
import com.itsluminous.samaroh.core.database.entity.BusinessEntity
import com.itsluminous.samaroh.core.database.entity.BusinessMemberEntity
import com.itsluminous.samaroh.core.database.entity.BusinessSettingsEntity
import com.itsluminous.samaroh.core.database.entity.DateBlockEntity
import com.itsluminous.samaroh.core.database.entity.ExpenseAttachmentEntity
import com.itsluminous.samaroh.core.database.entity.ExpenseEntity
import com.itsluminous.samaroh.core.database.entity.GoogleAccountLinkEntity
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
import com.itsluminous.samaroh.core.model.ExpenseAttachment
import com.itsluminous.samaroh.core.model.GoogleAccountLink
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.MasterItem
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.core.model.PaymentReminder

/*
 * Mechanical model → Room-entity mapping for pulled rows. The core:data mappers are
 * internal to that module (frozen contract), so the sync engine keeps its own copy;
 * field sets are identical by contract (ADR-001).
 */

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

internal fun BusinessMember.toEntity() =
    BusinessMemberEntity(
        id,
        businessId,
        invitedEmail,
        userId,
        displayName,
        isOwner,
        status,
        permissions,
        createdAt,
        updatedAt,
        deletedAt,
    )

internal fun BusinessSettings.toEntity() = BusinessSettingsEntity(businessId, gcalSyncEnabled, backupFrequency, lastBackupAt, updatedAt)

internal fun GoogleAccountLink.toEntity() = GoogleAccountLinkEntity(userId, email, scopes, driveRootFolderId, calendarId, updatedAt)

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

internal fun DateBlock.toEntity() = DateBlockEntity(id, businessId, startDate, endDate, reason, createdBy, createdAt, updatedAt, deletedAt)

internal fun BookingPayment.toEntity() =
    BookingPaymentEntity(
        id,
        bookingId,
        businessId,
        amountPaise,
        paidOn,
        method,
        notes,
        createdBy,
        createdAt,
        updatedAt,
        deletedAt,
    )

internal fun PaymentReminder.toEntity() =
    PaymentReminderEntity(
        id,
        bookingId,
        businessId,
        remindOn,
        status,
        amountDueSnapshotPaise,
        createdAt,
        updatedAt,
        deletedAt,
    )

internal fun Party.toEntity() = PartyEntity(id, businessId, name, phone, notes, createdAt, updatedAt, deletedAt)

internal fun Expense.toEntity() =
    ExpenseEntity(
        id,
        businessId,
        partyId,
        direction,
        amountPaise,
        expenseDate,
        notes,
        createdBy,
        createdAt,
        updatedAt,
        deletedAt,
    )

internal fun ExpenseAttachment.toEntity(localCachePath: String?) =
    ExpenseAttachmentEntity(
        id,
        expenseId,
        businessId,
        driveFileId,
        mimeType,
        fileName,
        localCachePath,
        createdAt,
        deletedAt,
    )

internal fun MasterItem.toEntity() = MasterItemEntity(id, businessId, name, unit, imagePath, driveImageId, createdAt, updatedAt, deletedAt)

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
