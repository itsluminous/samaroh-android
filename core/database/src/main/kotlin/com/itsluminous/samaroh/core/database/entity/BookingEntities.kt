package com.itsluminous.samaroh.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.PaymentMethod
import com.itsluminous.samaroh.core.model.ReminderKind
import com.itsluminous.samaroh.core.model.ReminderStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity(
    tableName = "bookings",
    indices = [Index(value = ["business_id", "start_date"])],
)
data class BookingEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "event_icon") val eventIcon: String,
    @ColumnInfo(name = "customer_name") val customerName: String,
    @ColumnInfo(name = "customer_phone") val customerPhone: String? = null,
    @ColumnInfo(name = "start_date") val startDate: LocalDate,
    @ColumnInfo(name = "end_date") val endDate: LocalDate,
    @ColumnInfo(name = "start_time") val startTime: LocalTime? = null,
    @ColumnInfo(name = "end_time") val endTime: LocalTime? = null,
    /** Long paise (ADR-002); Postgres column `total_amount` is numeric rupees. */
    @ColumnInfo(name = "total_amount") val totalAmountPaise: Long = 0,
    @ColumnInfo(name = "security_deposit") val securityDepositPaise: Long = 0,
    val source: BookingSource? = null,
    val notes: String? = null,
    val status: BookingStatus = BookingStatus.CONFIRMED,
    @ColumnInfo(name = "gcal_event_id") val gcalEventId: String? = null,
    @ColumnInfo(name = "invoice_number") val invoiceNumber: String? = null,
    /** Palette key from `shared/booking-colors.json` (ADR-030); NULL = default themed look. */
    val color: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

@Entity(
    tableName = "date_blocks",
    indices = [Index(value = ["business_id", "start_date"])],
)
data class DateBlockEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "start_date") val startDate: LocalDate,
    @ColumnInfo(name = "end_date") val endDate: LocalDate,
    val reason: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

@Entity(
    tableName = "booking_payments",
    indices = [Index(value = ["booking_id"]), Index(value = ["business_id"])],
)
data class BookingPaymentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "booking_id") val bookingId: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    /** Long paise (ADR-002); must be > 0 (enforced at the repository layer + Postgres check). */
    val amountPaise: Long,
    @ColumnInfo(name = "paid_on") val paidOn: LocalDate,
    val method: PaymentMethod = PaymentMethod.CASH,
    val notes: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

@Entity(
    tableName = "payment_reminders",
    indices = [Index(value = ["booking_id"]), Index(value = ["business_id", "remind_on"])],
)
data class PaymentReminderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "booking_id") val bookingId: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "remind_on") val remindOn: LocalDate,
    val status: ReminderStatus = ReminderStatus.PENDING,
    @ColumnInfo(name = "amount_due_snapshot") val amountDueSnapshotPaise: Long,
    /**
     * LOCAL-ONLY reminder kind (ADR-020): `payment` confirmation vs tentative-booking
     * `follow_up`. Never synced — pulls preserve the local value (same pattern as
     * `expense_attachments.local_cache_path`). Added in schema v2.
     */
    @ColumnInfo(name = "kind", defaultValue = "payment") val kind: ReminderKind = ReminderKind.PAYMENT,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

/**
 * Per-business booking event-type preset — mirror of `event_types` (shared migration
 * 006, ADR-032). `label`/`icon` are plain-text user data; bookings snapshot them at
 * save time and never reference this table, so preset edits leave old bookings intact.
 */
@Entity(
    tableName = "event_types",
    indices = [Index(value = ["business_id", "sort_order"])],
)
data class EventTypeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    val label: String,
    val icon: String,
    /** Default `booking-colors.json` key (ADR-031); NULL = standard themed look. */
    val color: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)
