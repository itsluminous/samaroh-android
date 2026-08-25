package com.itsluminous.samaroh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    @SerialName("created_by") val createdBy: String,
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(InstantSerializer::class) val updatedAt: Instant,
    @SerialName("deleted_at") @Serializable(InstantSerializer::class) val deletedAt: Instant? = null,
)

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
