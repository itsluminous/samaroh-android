package com.itsluminous.samaroh.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.itsluminous.samaroh.core.database.entity.ExpenseAttachmentEntity
import com.itsluminous.samaroh.core.database.entity.ExpenseEntity
import com.itsluminous.samaroh.core.database.entity.InventoryTransactionEntity
import com.itsluminous.samaroh.core.database.entity.MasterItemEntity
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.database.entity.PartyEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/** A party row plus its computed running balance. */
data class PartyWithBalance(
    @Embedded val party: PartyEntity,
    /**
     * Net paise: Σ(paid) − Σ(received). Positive = the business has given more than it got
     * (the party owes / has been paid that much net); shown red. Negative shown green.
     */
    val netBalancePaise: Long,
)

@Dao
interface PartyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(party: PartyEntity)

    @Query("SELECT * FROM parties WHERE id = :id")
    suspend fun byId(id: String): PartyEntity?

    /**
     * The Expenses-tab home list: live parties with their computed net balance (never
     * stored — always derived from live expense rows).
     */
    @Query(
        """
        SELECT p.*, COALESCE(SUM(
            CASE e.direction
                WHEN 'paid' THEN e.amountPaise
                WHEN 'received' THEN -e.amountPaise
            END), 0) AS netBalancePaise
        FROM parties p
        LEFT JOIN expenses e ON e.party_id = p.id AND e.deleted_at IS NULL
        WHERE p.business_id = :businessId AND p.deleted_at IS NULL
        GROUP BY p.id
        ORDER BY p.name COLLATE NOCASE ASC
        """,
    )
    fun partiesWithBalance(businessId: String): Flow<List<PartyWithBalance>>

    /** Type-ahead suggestion source (debounced in the UI layer). */
    @Query(
        """
        SELECT * FROM parties
        WHERE business_id = :businessId AND deleted_at IS NULL AND name LIKE '%' || :query || '%'
        ORDER BY name COLLATE NOCASE ASC LIMIT :limit
        """,
    )
    suspend fun searchByName(
        businessId: String,
        query: String,
        limit: Int = 10,
    ): List<PartyEntity>

    @Query("UPDATE parties SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun tombstone(
        id: String,
        at: Instant,
    )
}

/** One party's most recent live entry time — drives the "last entry" relative time on the home list. */
data class PartyLastEntryRow(
    val partyId: String,
    val lastEntryAt: Instant?,
)

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(expense: ExpenseEntity)

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun byId(id: String): ExpenseEntity?

    /** Live variant of [totalPaise] — drives the "You gave"/"You got" header totals card (W1-B additive; ADR-007). */
    @Query(
        """
        SELECT COALESCE(SUM(amountPaise), 0) FROM expenses
        WHERE business_id = :businessId AND direction = :direction AND deleted_at IS NULL
        """,
    )
    fun totalPaiseFlow(
        businessId: String,
        direction: String,
    ): Flow<Long>

    /** Most recent live entry per party (W1-B additive; ADR-007). */
    @Query(
        """
        SELECT party_id AS partyId, MAX(created_at) AS lastEntryAt FROM expenses
        WHERE business_id = :businessId AND deleted_at IS NULL
        GROUP BY party_id
        """,
    )
    fun lastEntryPerParty(businessId: String): Flow<List<PartyLastEntryRow>>

    @Query(
        """
        SELECT * FROM expenses
        WHERE party_id = :partyId AND deleted_at IS NULL
        ORDER BY expense_date DESC, created_at DESC
        """,
    )
    fun entriesForParty(partyId: String): Flow<List<ExpenseEntity>>

    /**
     * Live entries of every party dated in [from]..[to] — the cross-party input of the
     * expense-summary and profit reports (W2-A additive; ADR-019).
     */
    @Query(
        """
        SELECT * FROM expenses
        WHERE business_id = :businessId
          AND expense_date >= :from AND expense_date <= :to
          AND deleted_at IS NULL
        ORDER BY expense_date ASC
        """,
    )
    fun expensesBetween(
        businessId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<ExpenseEntity>>

    @Query(
        """
        SELECT COALESCE(SUM(amountPaise), 0) FROM expenses
        WHERE business_id = :businessId AND direction = :direction AND deleted_at IS NULL
        """,
    )
    suspend fun totalPaise(
        businessId: String,
        direction: String,
    ): Long

    @Query("UPDATE expenses SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun tombstone(
        id: String,
        at: Instant,
    )
}

@Dao
interface ExpenseAttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attachment: ExpenseAttachmentEntity)

    /** ADDITIVE W1-E (ADR-007): lets the sync engine preserve `local_cache_path` when applying pulled rows. */
    @Query("SELECT * FROM expense_attachments WHERE id = :id")
    suspend fun byId(id: String): ExpenseAttachmentEntity?

    @Query("SELECT * FROM expense_attachments WHERE expense_id = :expenseId AND deleted_at IS NULL ORDER BY created_at ASC")
    fun attachmentsForExpense(expenseId: String): Flow<List<ExpenseAttachmentEntity>>

    /** All live attachments across a party's entries — ledger-row thumbnails in one query (W1-B additive; ADR-007). */
    @Query(
        """
        SELECT a.* FROM expense_attachments a
        JOIN expenses e ON e.id = a.expense_id
        WHERE e.party_id = :partyId AND a.deleted_at IS NULL
        ORDER BY a.created_at ASC
        """,
    )
    fun attachmentsForParty(partyId: String): Flow<List<ExpenseAttachmentEntity>>

    @Query("UPDATE expense_attachments SET deleted_at = :at WHERE id = :id")
    suspend fun tombstone(
        id: String,
        at: Instant,
    )
}

@Dao
interface MasterItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MasterItemEntity)

    @Query("SELECT * FROM master_items WHERE business_id = :businessId AND deleted_at IS NULL ORDER BY name COLLATE NOCASE ASC")
    fun itemsForBusiness(businessId: String): Flow<List<MasterItemEntity>>

    /** Type-ahead suggestion source for the transaction dialog. */
    @Query(
        """
        SELECT * FROM master_items
        WHERE business_id = :businessId AND deleted_at IS NULL AND name LIKE '%' || :query || '%'
        ORDER BY name COLLATE NOCASE ASC LIMIT :limit
        """,
    )
    suspend fun searchByName(
        businessId: String,
        query: String,
        limit: Int = 10,
    ): List<MasterItemEntity>

    @Query("UPDATE master_items SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun tombstone(
        id: String,
        at: Instant,
    )
}

/**
 * One row of the Current Inventory screen (§4.3), aggregated per live master item:
 * stock = Σ(add qty) − Σ(remove qty); value = Σ(remaining_quantity × unit_price) over
 * open `add` lots (FIFO valuation — mirrors the canonical Postgres helper). Added by
 * W1-C (docs/decisions.md ADR-007).
 */
data class CurrentInventoryRow(
    val masterItemId: String,
    val name: String,
    val unit: String,
    val imagePath: String?,
    val currentQuantity: Double,
    /** Long paise (ADR-002); per-lot products are rounded to whole paise. */
    val totalValuePaise: Long,
    val lastTransactionAt: Instant?,
)

@Dao
interface InventoryTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(txn: InventoryTransactionEntity)

    /** Live master items with computed stock, FIFO value and last movement (ADR-007). */
    @Query(
        """
        SELECT mi.id AS masterItemId, mi.name AS name, mi.unit AS unit, mi.image_path AS imagePath,
            COALESCE(SUM(CASE t.transaction_type WHEN 'add' THEN t.quantity WHEN 'remove' THEN -t.quantity END), 0)
                AS currentQuantity,
            CAST(ROUND(COALESCE(SUM(
                CASE WHEN t.transaction_type = 'add' THEN t.remaining_quantity * t.unit_price ELSE 0 END), 0)) AS INTEGER)
                AS totalValuePaise,
            MAX(t.transaction_date) AS lastTransactionAt
        FROM master_items mi
        LEFT JOIN inventory_transactions t ON t.master_item_id = mi.id AND t.deleted_at IS NULL
        WHERE mi.business_id = :businessId AND mi.deleted_at IS NULL
        GROUP BY mi.id
        ORDER BY mi.name COLLATE NOCASE ASC
        """,
    )
    fun currentInventory(businessId: String): Flow<List<CurrentInventoryRow>>

    /**
     * Total transaction rows ever recorded for an item, INCLUDING tombstoned ones — the
     * can-delete rule input: a master item is deletable only while this is 0 (tombstoned
     * transactions still exist server-side, so they keep blocking deletion). ADR-007.
     */
    @Query("SELECT COUNT(*) FROM inventory_transactions WHERE master_item_id = :masterItemId")
    suspend fun transactionCountForItem(masterItemId: String): Int

    @Query(
        """
        SELECT * FROM inventory_transactions
        WHERE business_id = :businessId AND master_item_id = :masterItemId AND deleted_at IS NULL
        ORDER BY transaction_date DESC
        """,
    )
    fun transactionsForItem(
        businessId: String,
        masterItemId: String,
    ): Flow<List<InventoryTransactionEntity>>

    /**
     * FIFO open lots: `add` transactions with unconsumed remainder, oldest first — the
     * consumption order for `remove` transactions (mirrors the Postgres partial index).
     */
    @Query(
        """
        SELECT * FROM inventory_transactions
        WHERE business_id = :businessId AND master_item_id = :masterItemId
          AND transaction_type = 'add' AND remaining_quantity > 0 AND deleted_at IS NULL
        ORDER BY transaction_date ASC
        """,
    )
    suspend fun openAddLotsFifo(
        businessId: String,
        masterItemId: String,
    ): List<InventoryTransactionEntity>

    /**
     * Live `add` transactions (stock purchases) of every item with
     * `transaction_date` in [fromInclusive, toExclusive) — the inventory-purchases
     * input of the money reports (ADR-026). Instants compare as epoch millis.
     */
    @Query(
        """
        SELECT * FROM inventory_transactions
        WHERE business_id = :businessId AND transaction_type = 'add' AND deleted_at IS NULL
          AND transaction_date >= :fromInclusive AND transaction_date < :toExclusive
        ORDER BY transaction_date ASC
        """,
    )
    fun addTransactionsBetween(
        businessId: String,
        fromInclusive: Instant,
        toExclusive: Instant,
    ): Flow<List<InventoryTransactionEntity>>

    /** Current stock = Σ(add qty) − Σ(remove qty) over live rows. */
    @Query(
        """
        SELECT COALESCE(SUM(CASE transaction_type WHEN 'add' THEN quantity WHEN 'remove' THEN -quantity END), 0)
        FROM inventory_transactions
        WHERE business_id = :businessId AND master_item_id = :masterItemId AND deleted_at IS NULL
        """,
    )
    suspend fun currentStock(
        businessId: String,
        masterItemId: String,
    ): Double

    @Query("UPDATE inventory_transactions SET remaining_quantity = :remainingQuantity, updated_at = :at WHERE id = :id")
    suspend fun updateRemainingQuantity(
        id: String,
        remainingQuantity: Double,
        at: Instant,
    )

    @Query("UPDATE inventory_transactions SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun tombstone(
        id: String,
        at: Instant,
    )
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun enqueue(entry: OutboxEntity): Long

    /** FIFO head of the queue — autoincrement id IS the enqueue order. */
    @Query("SELECT * FROM outbox ORDER BY id ASC LIMIT :limit")
    suspend fun nextBatch(limit: Int = 50): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun pendingCount(): Flow<Int>

    @Query("UPDATE outbox SET attempt_count = attempt_count + 1, last_error = :error WHERE id = :id")
    suspend fun recordFailure(
        id: Long,
        error: String,
    )

    /** Called after a successful push — outbox rows are removed, not tombstoned (local-only table). */
    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun remove(id: Long)

    /*
     * ADDITIVE W1-E methods (docs/decisions.md ADR-007): LWW conflict detection and the
     * Settings → Sync status screen.
     */

    /** Pending ops for one entity, FIFO — LWW conflict-resolution input (§8). */
    @Query("SELECT * FROM outbox WHERE entity_type = :entityType AND entity_id = :entityId ORDER BY id ASC")
    suspend fun pendingForEntity(
        entityType: String,
        entityId: String,
    ): List<OutboxEntity>

    /** Rewrites a rebased op's payload and clears its error so it is pushed again. */
    @Query("UPDATE outbox SET payload_json = :payloadJson, last_error = NULL WHERE id = :id")
    suspend fun rewritePayload(
        id: Long,
        payloadJson: String,
    )

    /** Per-item sync errors (RLS rejections etc.) for the sync-status screen; retriable. */
    @Query("SELECT * FROM outbox WHERE last_error IS NOT NULL ORDER BY id ASC")
    fun erroredEntries(): Flow<List<OutboxEntity>>

    /** Every queued op in push (FIFO) order — the Sync status pending list (ADR-022). */
    @Query("SELECT * FROM outbox ORDER BY id ASC")
    fun pendingEntries(): Flow<List<OutboxEntity>>
}
