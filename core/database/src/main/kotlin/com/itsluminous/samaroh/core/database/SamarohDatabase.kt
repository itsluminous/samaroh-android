package com.itsluminous.samaroh.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import com.itsluminous.samaroh.core.database.dao.SyncDisplayDao
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
    version = 5,
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

    abstract fun syncDisplayDao(): SyncDisplayDao

    companion object {
        const val DATABASE_NAME = "samaroh.db"

        /**
         * v1 → v2 (ADR-020): local-only `payment_reminders.kind` discriminator
         * (`payment` | `follow_up`) for tentative-booking follow-up reminders.
         */
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE payment_reminders ADD COLUMN kind TEXT NOT NULL DEFAULT 'payment'",
                    )
                }
            }

        /**
         * v2 → v3 (ADR-024): keyset tie-breaker `sync_cursors.last_pulled_id`. NULL on
         * existing rows — the next pull re-fetches rows AT the stored timestamp, which
         * recovers rows lost to the old timestamp-only cursor.
         */
        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE sync_cursors ADD COLUMN last_pulled_id TEXT",
                    )
                }
            }

        /**
         * v3 → v4 (ADR-027): `parties.business_related` flag mirroring shared migration
         * 004 — existing parties default to business-related (counted in money reports).
         */
        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE parties ADD COLUMN business_related INTEGER NOT NULL DEFAULT 1",
                    )
                }
            }

        /**
         * v4 → v5 (ADR-030): nullable `bookings.color` palette key mirroring shared
         * migration 005 — NULL keeps the default themed calendar look.
         */
        val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE bookings ADD COLUMN color TEXT",
                    )
                }
            }
    }
}
