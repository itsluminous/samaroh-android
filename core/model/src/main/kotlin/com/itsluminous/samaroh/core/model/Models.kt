package com.itsluminous.samaroh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Domain models — exact mirrors of the canonical Postgres schema tables
 * (shared/supabase/migrations/001_schema.sql). FROZEN CONTRACT (docs/decisions.md ADR-001).
 *
 * Conventions:
 * - ids are client-generated UUID strings (offline creation);
 * - money is `Long` **paise** in fields suffixed `Paise` (ADR-002) — the sync layer converts
 *   to/from Postgres `numeric` decimal rupees at the wire boundary;
 * - `deletedAt != null` means tombstoned (soft delete — synced rows are never hard-deleted);
 * - `updatedAt` drives last-write-wins conflict resolution (§8).
 */
@Serializable
data class Business(
    val id: String,
    val name: String,
    @SerialName("business_type") val businessType: String = "Marriage Hall",
    val address: String? = null,
    @SerialName("owner_name") val ownerName: String,
    @SerialName("logo_path") val logoPath: String? = null,
    val currency: String = "INR",
    @SerialName("invoice_prefix") val invoicePrefix: String = "INV",
    @SerialName("invoice_counter") val invoiceCounter: Int = 0,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
)

@Serializable
data class BusinessMember(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("invited_email") val invitedEmail: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("is_owner") val isOwner: Boolean = false,
    val status: MemberStatus = MemberStatus.INVITED,
    val permissions: MemberPermissions = MemberPermissions(),
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
)

/**
 * Client-visible projection of the `google_accounts` table. The `refresh_token_cipher`
 * column deliberately has NO counterpart here: refresh tokens never leave server-side
 * storage (§6 security; docs/decisions.md ADR-003).
 */
@Serializable
data class GoogleAccountLink(
    @SerialName("user_id") val userId: String,
    val email: String,
    val scopes: List<String> = emptyList(),
    @SerialName("drive_root_folder_id") val driveRootFolderId: String? = null,
    @SerialName("calendar_id") val calendarId: String? = null,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
)

/**
 * One per-business booking event-type preset — mirror of the `event_types` table
 * (shared migration 006; docs/decisions.md ADR-032). `label` and `icon` are plain-text
 * user data (NOT string-catalog keys — see shared/docs/event-type-presets.md); bookings
 * snapshot them into `bookings.event_type`/`event_icon` at save time and are never
 * re-pointed at this table, so editing or deleting a preset leaves old bookings intact.
 */
@Serializable
data class EventType(
    val id: String,
    @SerialName("business_id") val businessId: String,
    /** Plain-text display name, unique per business among live rows. */
    val label: String,
    /** Emoji shown in the picker, calendar and booking form. */
    val icon: String,
    /**
     * The type's DEFAULT calendar colour — a `shared/booking-colors.json` key (ADR-031);
     * NULL = the standard themed look.
     */
    val color: String? = null,
    /** Display order in the picker and manage screen (ascending). */
    @SerialName("sort_order") val sortOrder: Int = 0,
    /**
     * What the preset is for (ADR-041): a real customer booking or a calendar-only
     * marker. Defaulted so rows pulled from a server without the `kind` column decode
     * as ordinary booking presets (same additive-column pattern as ADR-027/030).
     */
    val kind: EventTypeKind = EventTypeKind.BOOKING,
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
)

@Serializable
data class Booking(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("event_icon") val eventIcon: String,
    @SerialName("customer_name") val customerName: String,
    @SerialName("customer_phone") val customerPhone: String? = null,
    @SerialName("start_date") @Serializable(LocalDateSerializer::class) val startDate: LocalDate,
    @SerialName("end_date") @Serializable(LocalDateSerializer::class) val endDate: LocalDate,
    @SerialName("start_time") @Serializable(LocalTimeSerializer::class) val startTime: LocalTime? = null,
    @SerialName("end_time") @Serializable(LocalTimeSerializer::class) val endTime: LocalTime? = null,
    @SerialName("total_amount") val totalAmountPaise: Long = 0,
    @SerialName("security_deposit") val securityDepositPaise: Long = 0,
    val source: BookingSource? = null,
    val notes: String? = null,
    val status: BookingStatus = BookingStatus.CONFIRMED,
    @SerialName("gcal_event_id") val gcalEventId: String? = null,
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    /**
     * Palette key from `shared/booking-colors.json` (ADR-030); NULL = default themed
     * look. Defaulted so pre-005 server rows (no `color` column) decode unchanged.
     */
    @SerialName("color") val color: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
)

/**
 * The icon a booking renders with EVERYWHERE (calendar cells/pills, agenda, card title,
 * calendar-sync event title): tentative bookings show 👤 regardless of event type, so
 * unconfirmed slots are recognizable at a glance; confirming reverts to the event icon.
 * Additive presentation helper (ADR-020) — the stored `event_icon` column is untouched.
 */
val Booking.displayIcon: String
    get() = if (status == BookingStatus.TENTATIVE) TENTATIVE_ICON else eventIcon

/** The tentative-booking glyph used by [Booking.displayIcon]. */
const val TENTATIVE_ICON: String = "👤"

@Serializable
data class DateBlock(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("start_date") @Serializable(LocalDateSerializer::class) val startDate: LocalDate,
    @SerialName("end_date") @Serializable(LocalDateSerializer::class) val endDate: LocalDate,
    val reason: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
)

@Serializable
data class BookingPayment(
    val id: String,
    @SerialName("booking_id") val bookingId: String,
    @SerialName("business_id") val businessId: String,
    val amountPaise: Long,
    @SerialName("paid_on") @Serializable(LocalDateSerializer::class) val paidOn: LocalDate,
    val method: PaymentMethod = PaymentMethod.CASH,
    val notes: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
)

@Serializable
data class PaymentReminder(
    val id: String,
    @SerialName("booking_id") val bookingId: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("remind_on") @Serializable(LocalDateSerializer::class) val remindOn: LocalDate,
    val status: ReminderStatus = ReminderStatus.PENDING,
    @SerialName("amount_due_snapshot") val amountDueSnapshotPaise: Long,
    /**
     * LOCAL-ONLY reminder kind (ADR-020): payment confirmation vs tentative-booking
     * follow-up. `@Transient` keeps it out of outbox/sync payloads — the canonical
     * Postgres table has no such column.
     */
    @Transient val kind: ReminderKind = ReminderKind.PAYMENT,
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
)

@Serializable
data class Party(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val name: String,
    val phone: String? = null,
    val notes: String? = null,
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
    /**
     * false = personal party: its entries are excluded from the money reports and shown
     * only in the Personal-expenses report (ADR-027). Defaulted so rows pulled from a
     * server without migration 004 decode as business-related.
     */
    @SerialName("business_related") val businessRelated: Boolean = true,
)

@Serializable
data class Expense(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("party_id") val partyId: String,
    val direction: ExpenseDirection = ExpenseDirection.PAID,
    val amountPaise: Long,
    @SerialName("expense_date") @Serializable(LocalDateSerializer::class) val expenseDate: LocalDate,
    val notes: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
)

@Serializable
data class ExpenseAttachment(
    val id: String,
    @SerialName("expense_id") val expenseId: String,
    @SerialName("business_id") val businessId: String,
    /** Google Drive is the authoritative store; null while the upload is pending in the outbox. */
    @SerialName("drive_file_id") val driveFileId: String? = null,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
)

@Serializable
data class MasterItem(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val name: String,
    /** 'pcs' | 'qty' | 'kg' | free text. */
    val unit: String,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("drive_image_id") val driveImageId: String? = null,
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
)

@Serializable
data class InventoryTransaction(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("master_item_id") val masterItemId: String,
    @SerialName("transaction_type") val transactionType: TxnType,
    /** Postgres numeric(10,3); 3 decimal places is within double precision. */
    val quantity: Double,
    @SerialName("unit_price") val unitPricePaise: Long,
    /** FIFO lot tracking: how much of an `add` lot is still unconsumed. */
    @SerialName("remaining_quantity") val remainingQuantity: Double = 0.0,
    @SerialName("transaction_date") @Serializable(InstantSerializer::class) val transactionDate: Instant,
    val notes: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
)

@Serializable
data class BusinessSettings(
    @SerialName("business_id") val businessId: String,
    @SerialName("gcal_sync_enabled") val gcalSyncEnabled: Boolean = false,
    /** 'daily' | 'weekly' | 'monthly' | 'manual'. */
    @SerialName("backup_frequency") val backupFrequency: String = "weekly",
    @SerialName("last_backup_at") @Serializable(InstantSerializer::class) val lastBackupAt: Instant? = null,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
)
