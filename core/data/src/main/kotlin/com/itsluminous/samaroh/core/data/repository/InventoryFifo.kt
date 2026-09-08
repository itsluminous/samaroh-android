package com.itsluminous.samaroh.core.data.repository

import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.dao.InventoryTransactionDao
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.TxnType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * FIFO inventory calculator (spec §4.3, §2 FIFO note; docs/decisions.md ADR-007).
 * `remove` transactions consume the oldest open `add` lots by decrementing their
 * `remaining_quantity`; stock = Σ(add) − Σ(remove); value = Σ(remaining × unit price)
 * over open add lots. Runs in Kotlin against Room so it is correct fully offline.
 */

/** Quantities are numeric(10,3): treat differences below half a milliunit as zero. */
private const val QUANTITY_EPSILON = 0.0005

/** Rounds a quantity to the canonical 3 decimal places (numeric(10,3) parity). */
fun roundQuantity(value: Double): Double {
    val rounded = kotlin.math.round(value * 1000.0) / 1000.0
    return if (rounded == 0.0) 0.0 else rounded
}

/** One row of the Current Inventory list: live stock and FIFO valuation per item. */
data class CurrentInventoryLine(
    val masterItemId: String,
    val name: String,
    val unit: String,
    val imagePath: String?,
    /** Drive photo id (ADR-063) — the serving source when no local file exists. */
    val driveImageId: String?,
    val currentQuantity: Double,
    /** Long paise (ADR-002). */
    val totalValuePaise: Long,
    val lastTransactionAt: Instant?,
)

/** Read-side inventory queries beyond the frozen [InventoryRepository] contract (ADR-007). */
interface InventoryOverviewRepository {
    /** Live items with computed stock, FIFO value and last movement, sorted by name. */
    fun currentInventory(businessId: String): Flow<List<CurrentInventoryLine>>

    /**
     * The can-delete rule: a master item may be deleted only while NO inventory
     * transactions reference it (tombstoned ones included — they still exist upstream).
     */
    suspend fun canDeleteMasterItem(id: String): Boolean

    /**
     * Records a transaction exactly like [InventoryRepository.recordTransaction] and
     * returns its total value in paise: quantity × unit price for an add, the FIFO cost
     * of the consumed lots for a remove — the number the success snackbar surfaces
     * (additive read-back, docs/decisions.md ADR-021).
     */
    suspend fun recordTransactionForValue(txn: InventoryTransaction): Long

    /**
     * Edits an existing transaction's quantity/price/date/notes and FIFO-REPLAYS the
     * item's live history to rewrite every lot's `remaining_quantity` and every
     * remove's FIFO cost (additive, docs/decisions.md ADR-070). Rejected WITHOUT any
     * write when the edit would make cumulative stock negative at any point in the
     * history. A remove row's unit price is always recomputed from the replayed lots —
     * the [edited] value is ignored for removes.
     */
    suspend fun updateTransaction(edited: InventoryTransaction): TransactionMutationResult

    /**
     * Tombstones a transaction after the same FIFO replay validation + rewrite as
     * [updateTransaction] (ADR-070). Deleting an `add` that later removes depend on is
     * rejected when the remaining history would go negative at any point.
     */
    suspend fun deleteTransaction(id: String): TransactionMutationResult
}

/** Outcome of an existing-transaction edit/delete (ADR-070). */
enum class TransactionMutationResult {
    SAVED,

    /** The mutation would make cumulative stock negative somewhere in the history. */
    REJECTED_NEGATIVE_STOCK,
}

/**
 * Pure FIFO replay (ADR-070): walks an item's LIVE transactions in chronological order
 * and recomputes every `add` lot's `remaining_quantity` plus every `remove`'s FIFO
 * weighted unit cost from scratch — the same lot bookkeeping
 * [FifoInventoryRepository.recordTransactionForValue] applies incrementally, replayed
 * over the whole history after an edit/delete invalidates the stored remainders.
 */
object FifoReplay {
    /**
     * Returns the rewritten rows (same order as [chronological]) or NULL when the
     * history is invalid — a remove exceeds the stock available at its point in time
     * (cumulative stock would go negative).
     */
    fun replay(chronological: List<InventoryTransaction>): List<InventoryTransaction>? {
        // One open lot being consumed: index into `result` + its mutable remainder.
        class Lot(
            val index: Int,
            var remaining: Double,
            val unitPricePaise: Long,
        )

        val result = chronological.toMutableList()
        val openLots = ArrayDeque<Lot>()
        var stock = 0.0
        chronological.forEachIndexed { index, txn ->
            when (txn.transactionType) {
                TxnType.ADD -> {
                    val quantity = roundQuantity(txn.quantity)
                    openLots.addLast(Lot(index, quantity, txn.unitPricePaise))
                    stock = roundQuantity(stock + quantity)
                    result[index] = txn.copy(quantity = quantity, remainingQuantity = quantity)
                }
                TxnType.REMOVE -> {
                    val quantity = roundQuantity(txn.quantity)
                    // The negative-stock validation: this remove wants more than every
                    // prior add left over — the history cannot be satisfied.
                    if (quantity > stock + QUANTITY_EPSILON) return null
                    stock = roundQuantity(stock - quantity)
                    var remainingToRemove = quantity
                    var totalCostPaise = 0L
                    while (remainingToRemove > QUANTITY_EPSILON && openLots.isNotEmpty()) {
                        val lot = openLots.first()
                        val take = minOf(remainingToRemove, lot.remaining)
                        // Per-lot cost rounds to whole paise (ADR-002).
                        totalCostPaise += (take * lot.unitPricePaise).roundToLong()
                        lot.remaining = roundQuantity(lot.remaining - take)
                        remainingToRemove = roundQuantity(remainingToRemove - take)
                        result[lot.index] = result[lot.index].copy(remainingQuantity = lot.remaining)
                        if (lot.remaining <= QUANTITY_EPSILON) openLots.removeFirst()
                    }
                    // The remove carries the FIFO weighted-average unit cost; no remainder.
                    val weightedUnitPricePaise = (totalCostPaise.toDouble() / quantity).roundToLong()
                    result[index] = txn.copy(quantity = quantity, unitPricePaise = weightedUnitPricePaise, remainingQuantity = 0.0)
                }
            }
        }
        return result
    }

    /** Chronological replay order: `transaction_date`, then `created_at`, then `id`. */
    val CHRONOLOGICAL: Comparator<InventoryTransaction> =
        compareBy({ it.transactionDate }, { it.createdAt }, { it.id })
}

/**
 * FIFO-aware [InventoryRepository]: persistence and outbox behavior delegate to
 * [RoomInventoryRepository]; [recordTransaction] adds the lot bookkeeping the Wave 0
 * contract deliberately left to the inventory feature wave.
 */
@Singleton
class FifoInventoryRepository
    @Inject
    constructor(
        private val room: RoomInventoryRepository,
        private val txnDao: InventoryTransactionDao,
        private val outboxWriter: OutboxWriter,
        private val clock: Clock,
    ) : InventoryRepository by room,
        InventoryOverviewRepository {
        private val json = Json { encodeDefaults = true }

        override suspend fun recordTransaction(txn: InventoryTransaction) {
            recordTransactionForValue(txn)
        }

        override suspend fun recordTransactionForValue(txn: InventoryTransaction): Long {
            require(txn.quantity > 0) { "quantity must be positive" }
            return when (txn.transactionType) {
                // An add opens a fresh lot: its unconsumed remainder starts at the full quantity.
                TxnType.ADD -> {
                    val quantity = roundQuantity(txn.quantity)
                    room.recordTransaction(txn.copy(quantity = quantity, remainingQuantity = quantity))
                    (quantity * txn.unitPricePaise).roundToLong()
                }
                TxnType.REMOVE -> removeFifo(txn.copy(quantity = roundQuantity(txn.quantity)))
            }
        }

        /** Consumes FIFO lots and returns the total cost (paise) of the removed stock. */
        private suspend fun removeFifo(txn: InventoryTransaction): Long {
            val lots = txnDao.openAddLotsFifo(txn.businessId, txn.masterItemId)
            val available = lots.sumOf { it.remainingQuantity }
            require(txn.quantity <= available + QUANTITY_EPSILON) {
                "insufficient stock: available=$available requested=${txn.quantity}"
            }
            val now = clock.instant()
            var remainingToRemove = txn.quantity
            var totalCostPaise = 0L
            for (lot in lots) {
                if (remainingToRemove <= QUANTITY_EPSILON) break
                val take = minOf(remainingToRemove, lot.remainingQuantity)
                // Per-lot cost rounds to whole paise (ADR-002).
                totalCostPaise += (take * lot.unitPricePaise).roundToLong()
                remainingToRemove = roundQuantity(remainingToRemove - take)
                val newRemaining = roundQuantity(lot.remainingQuantity - take)
                txnDao.updateRemainingQuantity(lot.id, newRemaining, now)
                // The consumed lot changed state, so its new snapshot must sync too.
                val updatedLot = lot.copy(remainingQuantity = newRemaining, updatedAt = now).toModel()
                outboxWriter.enqueue(
                    "inventory_transactions",
                    updatedLot.id,
                    OutboxOperation.UPSERT,
                    json.encodeToString(InventoryTransaction.serializer(), updatedLot),
                )
            }
            check(abs(remainingToRemove) <= QUANTITY_EPSILON) { "FIFO consumption did not settle: $remainingToRemove left" }
            // The remove row carries the FIFO weighted-average unit cost; it never has a remainder.
            val weightedUnitPricePaise = (totalCostPaise.toDouble() / txn.quantity).roundToLong()
            room.recordTransaction(txn.copy(unitPricePaise = weightedUnitPricePaise, remainingQuantity = 0.0))
            return totalCostPaise
        }

        override fun currentInventory(businessId: String): Flow<List<CurrentInventoryLine>> =
            txnDao.currentInventory(businessId).map { rows ->
                rows.map {
                    CurrentInventoryLine(
                        masterItemId = it.masterItemId,
                        name = it.name,
                        unit = it.unit,
                        imagePath = it.imagePath,
                        driveImageId = it.driveImageId,
                        currentQuantity = roundQuantity(it.currentQuantity),
                        totalValuePaise = it.totalValuePaise,
                        lastTransactionAt = it.lastTransactionAt,
                    )
                }
            }

        override suspend fun canDeleteMasterItem(id: String): Boolean = txnDao.transactionCountForItem(id) == 0

        override suspend fun updateTransaction(edited: InventoryTransaction): TransactionMutationResult {
            require(edited.quantity > 0) { "quantity must be positive" }
            val stored = txnDao.byId(edited.id)?.takeIf { it.deletedAt == null } ?: return TransactionMutationResult.SAVED
            // Only qty/price/date/notes are editable; identity + attribution stay stored.
            val normalized =
                stored.toModel().copy(
                    quantity = roundQuantity(edited.quantity),
                    unitPricePaise = edited.unitPricePaise,
                    transactionDate = edited.transactionDate,
                    notes = edited.notes,
                )
            return replayAndPersist(normalized.businessId, normalized.masterItemId) { rows ->
                rows.map { row -> if (row.id == normalized.id) normalized else row }
            }
        }

        override suspend fun deleteTransaction(id: String): TransactionMutationResult {
            val stored = txnDao.byId(id)?.takeIf { it.deletedAt == null } ?: return TransactionMutationResult.SAVED
            val model = stored.toModel()
            val result =
                replayAndPersist(model.businessId, model.masterItemId) { rows ->
                    rows.filterNot { it.id == id }
                }
            if (result == TransactionMutationResult.SAVED) {
                // Tombstone, never hard-delete (sync-safe, same as every entity).
                val now = clock.instant()
                txnDao.tombstone(id, now)
                outboxWriter.enqueue("inventory_transactions", id, OutboxOperation.DELETE, deleteTombstonePayload(id, now))
            }
            return result
        }

        /**
         * The ADR-070 core: applies [mutate] to the item's live chronological history,
         * validates + replays FIFO from scratch, and persists ONLY the rows whose
         * synced fields changed (each upserted with a fresh `updated_at` and outboxed —
         * the same changed-lots outboxing [removeFifo] does incrementally). Nothing is
         * written when the replay rejects the history.
         */
        private suspend fun replayAndPersist(
            businessId: String,
            masterItemId: String,
            mutate: (List<InventoryTransaction>) -> List<InventoryTransaction>,
        ): TransactionMutationResult {
            val stored = txnDao.liveTransactionsChronological(businessId, masterItemId).map { it.toModel() }
            // An edit may move `transaction_date`, so the mutated set is re-sorted.
            val mutated = mutate(stored).sortedWith(FifoReplay.CHRONOLOGICAL)
            val replayed = FifoReplay.replay(mutated) ?: return TransactionMutationResult.REJECTED_NEGATIVE_STOCK
            val storedById = stored.associateBy { it.id }
            val now = clock.instant()
            for (row in replayed) {
                val before = storedById.getValue(row.id)
                if (before.copy(updatedAt = row.updatedAt) == row) continue
                val updated = row.copy(updatedAt = now)
                txnDao.upsert(updated.toEntity())
                outboxWriter.enqueue(
                    "inventory_transactions",
                    updated.id,
                    OutboxOperation.UPSERT,
                    json.encodeToString(InventoryTransaction.serializer(), updated),
                )
            }
            return TransactionMutationResult.SAVED
        }
    }

/** DELETE outbox payload: id + tombstone timestamp (parity with the Room repositories). */
private fun deleteTombstonePayload(
    id: String,
    at: Instant,
): String =
    Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("id", id)
            put("deleted_at", at.toString())
        },
    )
