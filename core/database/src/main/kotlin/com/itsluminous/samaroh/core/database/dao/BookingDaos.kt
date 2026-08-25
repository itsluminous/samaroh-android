package com.itsluminous.samaroh.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.itsluminous.samaroh.core.database.entity.BookingEntity
import com.itsluminous.samaroh.core.database.entity.BookingPaymentEntity
import com.itsluminous.samaroh.core.database.entity.DateBlockEntity
import com.itsluminous.samaroh.core.database.entity.PaymentReminderEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

@Dao
interface BookingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(booking: BookingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bookings: List<BookingEntity>)

    @Query("SELECT * FROM bookings WHERE id = :id")
    suspend fun byId(id: String): BookingEntity?

    /**
     * Live bookings whose date span overlaps [from]..[to] — the calendar month query.
     * Overlap: `start_date <= to AND end_date >= from` (ISO-8601 TEXT compares chronologically).
     */
    @Query(
        """
        SELECT * FROM bookings
        WHERE business_id = :businessId
          AND start_date <= :to AND end_date >= :from
          AND deleted_at IS NULL
        ORDER BY start_date ASC, created_at ASC
        """,
    )
    fun bookingsBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<BookingEntity>>

    /** Non-blocking conflict warning input: live bookings covering one date. */
    @Query(
        """
        SELECT COUNT(*) FROM bookings
        WHERE business_id = :businessId
          AND start_date <= :date AND end_date >= :date
          AND status != 'cancelled'
          AND deleted_at IS NULL
        """,
    )
    suspend fun countBookingsOn(
        businessId: String,
        date: LocalDate,
    ): Int

    /** Soft delete (tombstone) — synced rows are never hard-deleted (§8). */
    @Query("UPDATE bookings SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun tombstone(
        id: String,
        at: Instant,
    )
}

@Dao
interface DateBlockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(block: DateBlockEntity)

    @Query(
        """
        SELECT * FROM date_blocks
        WHERE business_id = :businessId
          AND start_date <= :to AND end_date >= :from
          AND deleted_at IS NULL
        ORDER BY start_date ASC
        """,
    )
    fun blocksBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<DateBlockEntity>>

    @Query("UPDATE date_blocks SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun tombstone(
        id: String,
        at: Instant,
    )
}

@Dao
interface BookingPaymentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(payment: BookingPaymentEntity)

    @Query(
        """
        SELECT * FROM booking_payments
        WHERE booking_id = :bookingId AND deleted_at IS NULL
        ORDER BY paid_on ASC, created_at ASC
        """,
    )
    fun paymentsForBooking(bookingId: String): Flow<List<BookingPaymentEntity>>

    /** due = bookings.total_amount − this sum. ALWAYS computed, never stored. */
    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM booking_payments WHERE booking_id = :bookingId AND deleted_at IS NULL")
    suspend fun totalPaidPaise(bookingId: String): Long

    @Query("UPDATE booking_payments SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun tombstone(
        id: String,
        at: Instant,
    )
}

@Dao
interface PaymentReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: PaymentReminderEntity)

    @Query(
        """
        SELECT * FROM payment_reminders
        WHERE business_id = :businessId AND status = 'pending' AND remind_on <= :onOrBefore AND deleted_at IS NULL
        ORDER BY remind_on ASC
        """,
    )
    fun duePendingReminders(
        businessId: String,
        onOrBefore: LocalDate,
    ): Flow<List<PaymentReminderEntity>>

    @Query("UPDATE payment_reminders SET status = :status, updated_at = :at WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
        at: Instant,
    )
}
