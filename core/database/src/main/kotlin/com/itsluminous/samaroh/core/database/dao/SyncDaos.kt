package com.itsluminous.samaroh.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.itsluminous.samaroh.core.database.entity.SyncConflictEntity
import com.itsluminous.samaroh.core.database.entity.SyncCursorEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/*
 * DAOs for the local-only sync bookkeeping tables — ADDITIVE W1-E change
 * (docs/decisions.md ADR-007).
 */

@Dao
interface SyncCursorDao {
    @Query("SELECT last_pulled_at FROM sync_cursors WHERE business_id = :businessId AND table_name = :tableName")
    suspend fun cursor(
        businessId: String,
        tableName: String,
    ): Instant?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SyncCursorEntity)
}

@Dao
interface SyncConflictDao {
    @Insert
    suspend fun insert(conflict: SyncConflictEntity): Long

    @Query("SELECT * FROM sync_conflicts ORDER BY occurred_at DESC, id DESC")
    fun conflictLog(): Flow<List<SyncConflictEntity>>

    /** Drives the in-app conflict banner state: banner shows while this is > 0. */
    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE acknowledged = 0")
    fun unacknowledgedCount(): Flow<Int>

    @Query("UPDATE sync_conflicts SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)
}

/**
 * Scalar lookups that turn an outbox/error row into a human display line for the
 * Settings → Sync status screen (ADR-022). Deliberately NO `deleted_at` filter:
 * a queued delete's row is already tombstoned locally but its name is still the
 * best identifier to show ("Delete booking — Sharma").
 */
@Dao
interface SyncDisplayDao {
    @Query("SELECT customer_name FROM bookings WHERE id = :id")
    suspend fun bookingCustomerName(id: String): String?

    @Query("SELECT start_date FROM bookings WHERE id = :id")
    suspend fun bookingStartDate(id: String): String?

    @Query("SELECT name FROM parties WHERE id = :id")
    suspend fun partyName(id: String): String?

    @Query("SELECT name FROM master_items WHERE id = :id")
    suspend fun masterItemName(id: String): String?

    @Query("SELECT name FROM businesses WHERE id = :id")
    suspend fun businessName(id: String): String?

    @Query("SELECT display_name FROM business_members WHERE id = :id")
    suspend fun memberDisplayName(id: String): String?

    @Query("SELECT file_name FROM expense_attachments WHERE id = :id")
    suspend fun attachmentFileName(id: String): String?

    @Query("SELECT party_id FROM expenses WHERE id = :id")
    suspend fun expensePartyId(id: String): String?

    @Query("SELECT booking_id FROM booking_payments WHERE id = :id")
    suspend fun paymentBookingId(id: String): String?

    @Query("SELECT amountPaise FROM booking_payments WHERE id = :id")
    suspend fun paymentAmountPaise(id: String): Long?

    @Query("SELECT booking_id FROM payment_reminders WHERE id = :id")
    suspend fun reminderBookingId(id: String): String?

    @Query("SELECT start_date FROM date_blocks WHERE id = :id")
    suspend fun dateBlockStartDate(id: String): String?

    @Query("SELECT master_item_id FROM inventory_transactions WHERE id = :id")
    suspend fun txnMasterItemId(id: String): String?
}
