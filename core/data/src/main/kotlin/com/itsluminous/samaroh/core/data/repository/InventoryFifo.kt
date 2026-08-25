package com.itsluminous.samaroh.core.data.repository

import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.dao.InventoryTransactionDao
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.TxnType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
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
            require(txn.quantity > 0) { "quantity must be positive" }
            when (txn.transactionType) {
                // An add opens a fresh lot: its unconsumed remainder starts at the full quantity.
                TxnType.ADD ->
                    room.recordTransaction(
                        txn.copy(quantity = roundQuantity(txn.quantity), remainingQuantity = roundQuantity(txn.quantity)),
                    )
                TxnType.REMOVE -> removeFifo(txn.copy(quantity = roundQuantity(txn.quantity)))
            }
        }

        private suspend fun removeFifo(txn: InventoryTransaction) {
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
        }

        override fun currentInventory(businessId: String): Flow<List<CurrentInventoryLine>> =
            txnDao.currentInventory(businessId).map { rows ->
                rows.map {
                    CurrentInventoryLine(
                        masterItemId = it.masterItemId,
                        name = it.name,
                        unit = it.unit,
                        imagePath = it.imagePath,
                        currentQuantity = roundQuantity(it.currentQuantity),
                        totalValuePaise = it.totalValuePaise,
                        lastTransactionAt = it.lastTransactionAt,
                    )
                }
            }

        override suspend fun canDeleteMasterItem(id: String): Boolean = txnDao.transactionCountForItem(id) == 0
    }
