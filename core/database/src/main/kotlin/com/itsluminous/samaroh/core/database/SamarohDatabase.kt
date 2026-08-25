package com.itsluminous.samaroh.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.itsluminous.samaroh.core.database.dao.BookingDao
import com.itsluminous.samaroh.core.database.dao.BookingPaymentDao
import com.itsluminous.samaroh.core.database.dao.BusinessDao
import com.itsluminous.samaroh.core.database.dao.BusinessMemberDao
import com.itsluminous.samaroh.core.database.dao.BusinessSettingsDao
import com.itsluminous.samaroh.core.database.dao.DateBlockDao
import com.itsluminous.samaroh.core.database.dao.ExpenseAttachmentDao
import com.itsluminous.samaroh.core.database.dao.ExpenseDao
import com.itsluminous.samaroh.core.database.dao.GoogleAccountLinkDao
import com.itsluminous.samaroh.core.database.dao.InventoryTransactionDao
import com.itsluminous.samaroh.core.database.dao.MasterItemDao
import com.itsluminous.samaroh.core.database.dao.OutboxDao
import com.itsluminous.samaroh.core.database.dao.PartyDao
import com.itsluminous.samaroh.core.database.dao.PaymentReminderDao
import com.itsluminous.samaroh.core.database.dao.SyncConflictDao
import com.itsluminous.samaroh.core.database.dao.SyncCursorDao
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
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.database.entity.PartyEntity
import com.itsluminous.samaroh.core.database.entity.PaymentReminderEntity
import com.itsluminous.samaroh.core.database.entity.SyncConflictEntity
import com.itsluminous.samaroh.core.database.entity.SyncCursorEntity

/**
 * Offline-first source of truth (§1.1): the UI only ever reads from this database;
 * Supabase is the sync target. Schema mirrors shared/supabase/migrations/001_schema.sql
 * plus the local-only `outbox` table (§8).
 */
@Database(
    entities = [
        BusinessEntity::class,
        BusinessMemberEntity::class,
        GoogleAccountLinkEntity::class,
        BusinessSettingsEntity::class,
        BookingEntity::class,
        DateBlockEntity::class,
        BookingPaymentEntity::class,
        PaymentReminderEntity::class,
        PartyEntity::class,
        ExpenseEntity::class,
        ExpenseAttachmentEntity::class,
        MasterItemEntity::class,
        InventoryTransactionEntity::class,
        OutboxEntity::class,
        SyncCursorEntity::class,
        SyncConflictEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SamarohDatabase : RoomDatabase() {
    abstract fun businessDao(): BusinessDao

    abstract fun businessMemberDao(): BusinessMemberDao

    abstract fun businessSettingsDao(): BusinessSettingsDao

    abstract fun googleAccountLinkDao(): GoogleAccountLinkDao

    abstract fun bookingDao(): BookingDao

    abstract fun dateBlockDao(): DateBlockDao

    abstract fun bookingPaymentDao(): BookingPaymentDao

    abstract fun paymentReminderDao(): PaymentReminderDao

    abstract fun partyDao(): PartyDao

    abstract fun expenseDao(): ExpenseDao

    abstract fun expenseAttachmentDao(): ExpenseAttachmentDao

    abstract fun masterItemDao(): MasterItemDao

    abstract fun inventoryTransactionDao(): InventoryTransactionDao

    abstract fun outboxDao(): OutboxDao

    abstract fun syncCursorDao(): SyncCursorDao

    abstract fun syncConflictDao(): SyncConflictDao

    companion object {
        const val DATABASE_NAME = "samaroh.db"
    }
}
