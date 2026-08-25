package com.itsluminous.samaroh.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.model.TxnType
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "parties",
    indices = [Index(value = ["business_id", "name"], unique = true)],
)
data class PartyEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    val name: String,
    val phone: String? = null,
    val notes: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

@Entity(
    tableName = "expenses",
    indices = [Index(value = ["party_id"]), Index(value = ["business_id", "expense_date"])],
)
data class ExpenseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "party_id") val partyId: String,
    val direction: ExpenseDirection = ExpenseDirection.PAID,
    /** Long paise (ADR-002). */
    val amountPaise: Long,
    @ColumnInfo(name = "expense_date") val expenseDate: LocalDate,
    val notes: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

/**
 * Attachment metadata only — the file itself lives in Google Drive; `local_cache_path`
 * is Room-only state (never synced) pointing at the on-device copy while upload pends.
 */
@Entity(
    tableName = "expense_attachments",
    indices = [Index(value = ["expense_id"])],
)
data class ExpenseAttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "drive_file_id") val driveFileId: String? = null,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "local_cache_path") val localCachePath: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

@Entity(
    tableName = "master_items",
    indices = [Index(value = ["business_id", "name"], unique = true)],
)
data class MasterItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    val name: String,
    val unit: String,
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    @ColumnInfo(name = "drive_image_id") val driveImageId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

@Entity(
    tableName = "inventory_transactions",
    indices = [Index(value = ["business_id", "master_item_id", "transaction_date"])],
)
data class InventoryTransactionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "master_item_id") val masterItemId: String,
    @ColumnInfo(name = "transaction_type") val transactionType: TxnType,
    val quantity: Double,
    /** Long paise (ADR-002). */
    @ColumnInfo(name = "unit_price") val unitPricePaise: Long,
    /** FIFO lot tracking: unconsumed remainder of an `add` lot. */
    @ColumnInfo(name = "remaining_quantity") val remainingQuantity: Double = 0.0,
    @ColumnInfo(name = "transaction_date") val transactionDate: Instant,
    val notes: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
)

/**
 * Local-only outbox (§8) — every local mutation queues a row here; the sync engine pushes
 * them FIFO. This table never syncs, so unlike synced entities it may use an
 * autoincrement PK: `ORDER BY id` IS the FIFO order (monotonic, collision-free).
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    /** "upsert" or "delete" (tombstone propagation). */
    val operation: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
)
