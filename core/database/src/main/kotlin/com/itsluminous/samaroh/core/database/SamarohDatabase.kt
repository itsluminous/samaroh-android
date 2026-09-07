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
import com.itsluminous.samaroh.core.database.dao.EventTypeDao
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
import com.itsluminous.samaroh.core.database.entity.EventTypeEntity
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
        EventTypeEntity::class,
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
    version = 11,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SamarohDatabase : RoomDatabase() {
    abstract fun businessDao(): BusinessDao

    abstract fun businessMemberDao(): BusinessMemberDao

    abstract fun businessSettingsDao(): BusinessSettingsDao

    abstract fun googleAccountLinkDao(): GoogleAccountLinkDao

    abstract fun eventTypeDao(): EventTypeDao

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

        /**
         * v5 → v6 (ADR-032): local `event_types` presets table mirroring shared
         * migration 006. NOT seeded here — existing businesses receive their rows from
         * the server (migration 006 seeded them); new businesses are seeded client-side
         * at creation. An offline pre-006 business that never syncs simply has no
         * presets until the manage screen adds some (the form's Custom entry remains).
         */
        val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS event_types (" +
                            "id TEXT NOT NULL PRIMARY KEY, " +
                            "business_id TEXT NOT NULL, " +
                            "label TEXT NOT NULL, " +
                            "icon TEXT NOT NULL, " +
                            "color TEXT, " +
                            "sort_order INTEGER NOT NULL DEFAULT 0, " +
                            "created_at INTEGER NOT NULL, " +
                            "updated_at INTEGER NOT NULL, " +
                            "deleted_at INTEGER)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_event_types_business_id_sort_order " +
                            "ON event_types (business_id, sort_order)",
                    )
                }
            }

        /**
         * v6 → v7 (ADR-041): `event_types.kind` ('booking' | 'marker') mirroring the
         * shared schema's additive column — existing presets default to 'booking'.
         * Servers not yet carrying the column are tolerated on pull (defaulted decode)
         * and hold pushes per-item until it lands, exactly like ADR-030's colour.
         */
        val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE event_types ADD COLUMN kind TEXT NOT NULL DEFAULT 'booking'",
                    )
                }
            }

        /**
         * v7 → v8 (ADR-048): `business_settings.gcal_calendar_id` — the per-business
         * app-created Google Calendar (named after the business). Mirrors the shared
         * schema's additive column (`scripts/alter-gcal-calendar-id.sql`). Servers not
         * yet carrying the column are tolerated: pulls decode via the defaulted model
         * field, pushes hold per-item (PGRST204) until the owner applies the alter —
         * the ADR-027/030 self-healing pattern.
         */
        val MIGRATION_7_8: Migration =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE business_settings ADD COLUMN gcal_calendar_id TEXT",
                    )
                }
            }

        /**
         * v8 → v9 (ADR-059): `expense_attachments.drive_permission_ensured` — DEVICE-ONLY
         * state (never synced, exactly like `local_cache_path`): whether this device has
         * ensured the bill's Drive file carries the anyone-with-link reader permission.
         * Existing rows default to 0 so the repair pass picks them up; the column has no
         * server counterpart and never appears in a sync payload.
         */
        val MIGRATION_8_9: Migration =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE expense_attachments ADD COLUMN drive_permission_ensured INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        /**
         * ADR-060: `sync_cursors.last_pulled_raw` — the pull cursor's timestamp exactly
         * as the server serialized it (microsecond precision), so the keyset `eq`
         * tie-breaker actually matches tied rows. Existing rows stay null; the next pull
         * re-fetches from the stored millisecond (idempotent) and rebuilds the position.
         */
        val MIGRATION_9_10: Migration =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE sync_cursors ADD COLUMN last_pulled_raw TEXT")
                }
            }

        /**
         * ADR-063: `master_items.drive_permission_ensured` — device-only flag (never
         * synced, exactly like the ADR-059 `expense_attachments` twin) marking that this
         * device confirmed the anyone-with-link reader permission on the item's Drive
         * photo. Existing rows start unset, so the repair pass covers every pre-ADR-063
         * Drive mirror retroactively.
         */
        val MIGRATION_10_11: Migration =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE master_items ADD COLUMN drive_permission_ensured INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }
    }
}
