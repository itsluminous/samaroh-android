package com.itsluminous.samaroh.feature.inventory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.repository.FifoInventoryRepository
import com.itsluminous.samaroh.core.data.repository.RoomInventoryRepository
import com.itsluminous.samaroh.core.data.repository.TransactionMutationResult
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.TxnType
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Records enqueued outbox operations without any sync machinery. */
private class RecordingOutbox : OutboxWriter {
    data class Entry(
        val entityType: String,
        val entityId: String,
        val operation: OutboxOperation,
        val payloadJson: String,
    )

    val entries = mutableListOf<Entry>()

    override suspend fun enqueue(
        entityType: String,
        entityId: String,
        operation: OutboxOperation,
        payloadJson: String,
    ) {
        entries += Entry(entityType, entityId, operation, payloadJson)
    }
}

/**
 * ADR-070: FIFO REPLAY correctness for transaction edit/delete — lot remainders and
 * remove costs are recomputed over the whole live history, negative-stock histories
 * are rejected without any write, deletes tombstone, and changed rows sync via outbox.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionMutationReplayTest {
    private lateinit var db: SamarohDatabase
    private lateinit var outbox: RecordingOutbox
    private lateinit var repository: FifoInventoryRepository

    private val clock = Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC)
    private val businessId = Fixtures.BUSINESS_ID
    private val itemId = "item-1"

    private val day1 = Instant.parse("2026-08-01T09:00:00Z")
    private val day5 = Instant.parse("2026-08-05T09:00:00Z")
    private val day10 = Instant.parse("2026-08-10T09:00:00Z")
    private val day15 = Instant.parse("2026-08-15T09:00:00Z")

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        outbox = RecordingOutbox()
        val room = RoomInventoryRepository(db.masterItemDao(), db.inventoryTransactionDao(), outbox, clock)
        repository = FifoInventoryRepository(room, db.inventoryTransactionDao(), outbox, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedItem() = repository.saveMasterItem(Fixtures.masterItem(id = itemId, name = "fixture-item"))

    private suspend fun record(
        id: String,
        type: TxnType,
        quantity: Double,
        unitPricePaise: Long,
        date: Instant,
    ) {
        repository.recordTransaction(
            Fixtures.inventoryTxn(
                masterItemId = itemId,
                id = id,
                type = type,
                quantity = quantity,
                unitPricePaise = unitPricePaise,
                remainingQuantity = 0.0,
                transactionDate = date,
            ),
        )
    }

    private suspend fun txn(id: String): InventoryTransaction =
        repository.transactionsForItem(businessId, itemId).first().first { it.id == id }

    private suspend fun allLive(): List<InventoryTransaction> = repository.transactionsForItem(businessId, itemId).first()

    // ── Edit cases ────────────────────────────────────────────────────────────────

    @Test
    fun `case 1 - editing an add's quantity up rewrites its remainder`() =
        runTest {
            seedItem()
            record("add-1", TxnType.ADD, 10.0, 100_00L, day1)
            record("rem-1", TxnType.REMOVE, 4.0, 0L, day10)

            val result = repository.updateTransaction(txn("add-1").copy(quantity = 20.0))

            assertThat(result).isEqualTo(TransactionMutationResult.SAVED)
            assertThat(txn("add-1").quantity).isEqualTo(20.0)
            // Replay: 20 in, 4 consumed → 16 left.
            assertThat(txn("add-1").remainingQuantity).isEqualTo(16.0)
            assertThat(repository.currentStock(businessId, itemId)).isEqualTo(16.0)
        }

    @Test
    fun `case 2 - editing an add below what later removes consumed is rejected`() =
        runTest {
            seedItem()
            record("add-1", TxnType.ADD, 10.0, 100_00L, day1)
            record("rem-1", TxnType.REMOVE, 8.0, 0L, day10)
            outbox.entries.clear()

            val result = repository.updateTransaction(txn("add-1").copy(quantity = 5.0))

            assertThat(result).isEqualTo(TransactionMutationResult.REJECTED_NEGATIVE_STOCK)
            // Nothing written, nothing outboxed.
            assertThat(txn("add-1").quantity).isEqualTo(10.0)
            assertThat(txn("add-1").remainingQuantity).isEqualTo(2.0)
            assertThat(outbox.entries).isEmpty()
        }

    @Test
    fun `case 3 - editing a remove beyond the stock available at its time is rejected`() =
        runTest {
            seedItem()
            record("add-1", TxnType.ADD, 10.0, 100_00L, day1)
            record("rem-1", TxnType.REMOVE, 4.0, 0L, day10)
            outbox.entries.clear()

            val result = repository.updateTransaction(txn("rem-1").copy(quantity = 11.0))

            assertThat(result).isEqualTo(TransactionMutationResult.REJECTED_NEGATIVE_STOCK)
            assertThat(txn("rem-1").quantity).isEqualTo(4.0)
            assertThat(txn("add-1").remainingQuantity).isEqualTo(6.0)
            assertThat(outbox.entries).isEmpty()
        }

    @Test
    fun `case 4 - editing a remove's quantity down restores the consumed lot`() =
        runTest {
            seedItem()
            record("add-1", TxnType.ADD, 10.0, 100_00L, day1)
            record("rem-1", TxnType.REMOVE, 8.0, 0L, day10)

            val result = repository.updateTransaction(txn("rem-1").copy(quantity = 2.0))

            assertThat(result).isEqualTo(TransactionMutationResult.SAVED)
            assertThat(txn("add-1").remainingQuantity).isEqualTo(8.0)
            // Weighted cost recomputed for the smaller remove: 2 × ₹100.
            assertThat(txn("rem-1").unitPricePaise).isEqualTo(100_00L)
            assertThat(repository.currentStock(businessId, itemId)).isEqualTo(8.0)
        }

    @Test
    fun `case 5 - moving a remove's date before its add is rejected`() =
        runTest {
            seedItem()
            record("add-1", TxnType.ADD, 10.0, 100_00L, day5)
            record("rem-1", TxnType.REMOVE, 4.0, 0L, day10)
            outbox.entries.clear()

            val result = repository.updateTransaction(txn("rem-1").copy(transactionDate = day1))

            assertThat(result).isEqualTo(TransactionMutationResult.REJECTED_NEGATIVE_STOCK)
            assertThat(txn("rem-1").transactionDate).isEqualTo(day10)
            assertThat(outbox.entries).isEmpty()
        }

    @Test
    fun `case 6 - editing an add's price reprices the later remove's FIFO cost`() =
        runTest {
            seedItem()
            record("add-1", TxnType.ADD, 10.0, 100_00L, day1)
            record("add-2", TxnType.ADD, 5.0, 200_00L, day5)
            record("rem-1", TxnType.REMOVE, 12.0, 0L, day10)
            // Sanity: original weighted cost (10×100 + 2×200)/12 → ₹116.67.
            assertThat(txn("rem-1").unitPricePaise).isEqualTo(11_667L)

            val result = repository.updateTransaction(txn("add-1").copy(unitPricePaise = 50_00L))

            assertThat(result).isEqualTo(TransactionMutationResult.SAVED)
            // Replayed: (10×50 + 2×200)/12 = 900/12 = ₹75.
            assertThat(txn("rem-1").unitPricePaise).isEqualTo(75_00L)
            assertThat(txn("add-2").remainingQuantity).isEqualTo(3.0)
        }

    @Test
    fun `case 7 - notes-only edit rewrites just that row and outboxes it once`() =
        runTest {
            seedItem()
            record("add-1", TxnType.ADD, 10.0, 100_00L, day1)
            record("rem-1", TxnType.REMOVE, 4.0, 0L, day10)
            outbox.entries.clear()

            val result = repository.updateTransaction(txn("add-1").copy(notes = "recount"))

            assertThat(result).isEqualTo(TransactionMutationResult.SAVED)
            assertThat(txn("add-1").notes).isEqualTo("recount")
            assertThat(txn("add-1").remainingQuantity).isEqualTo(6.0)
            val upserts = outbox.entries.filter { it.entityType == "inventory_transactions" }
            assertThat(upserts).hasSize(1)
            assertThat(upserts.single().entityId).isEqualTo("add-1")
        }

    // ── Delete cases ──────────────────────────────────────────────────────────────

    @Test
    fun `case 8 - deleting an add that later removes depend on is rejected`() =
        runTest {
            seedItem()
            record("add-1", TxnType.ADD, 10.0, 100_00L, day1)
            record("add-2", TxnType.ADD, 5.0, 200_00L, day5)
            record("rem-1", TxnType.REMOVE, 12.0, 0L, day10)
            outbox.entries.clear()

            val result = repository.deleteTransaction("add-1")

            assertThat(result).isEqualTo(TransactionMutationResult.REJECTED_NEGATIVE_STOCK)
            assertThat(allLive().map { it.id }).containsExactly("add-1", "add-2", "rem-1")
            assertThat(outbox.entries).isEmpty()
        }

    @Test
    fun `case 9 - deleting a remove tombstones it and restores the lots`() =
        runTest {
            seedItem()
            record("add-1", TxnType.ADD, 10.0, 100_00L, day1)
            record("rem-1", TxnType.REMOVE, 8.0, 0L, day10)
            outbox.entries.clear()

            val result = repository.deleteTransaction("rem-1")

            assertThat(result).isEqualTo(TransactionMutationResult.SAVED)
            assertThat(allLive().map { it.id }).containsExactly("add-1")
            assertThat(txn("add-1").remainingQuantity).isEqualTo(10.0)
            assertThat(repository.currentStock(businessId, itemId)).isEqualTo(10.0)
            // Tombstone DELETE outboxed for the removed row + UPSERT for the restored lot.
            val delete = outbox.entries.single { it.operation == OutboxOperation.DELETE }
            assertThat(delete.entityId).isEqualTo("rem-1")
            assertThat(delete.payloadJson).contains("deleted_at")
            val upsert = outbox.entries.single { it.operation == OutboxOperation.UPSERT }
            assertThat(upsert.entityId).isEqualTo("add-1")
            assertThat(upsert.payloadJson).contains("\"remaining_quantity\":10.0")
        }

    @Test
    fun `case 10 - deleting an unconsumed add succeeds and reroutes later removes`() =
        runTest {
            seedItem()
            record("add-1", TxnType.ADD, 10.0, 100_00L, day1)
            record("add-2", TxnType.ADD, 5.0, 200_00L, day5)
            record("rem-1", TxnType.REMOVE, 4.0, 0L, day10)

            val result = repository.deleteTransaction("add-1")

            assertThat(result).isEqualTo(TransactionMutationResult.SAVED)
            // The remove now consumes add-2 instead: 5 − 4 = 1 left, cost ₹200.
            assertThat(txn("add-2").remainingQuantity).isEqualTo(1.0)
            assertThat(txn("rem-1").unitPricePaise).isEqualTo(200_00L)
            assertThat(repository.currentStock(businessId, itemId)).isEqualTo(1.0)
        }

    @Test
    fun `case 11 - multi-lot history replays every remainder deterministically`() =
        runTest {
            seedItem()
            record("add-1", TxnType.ADD, 10.0, 100_00L, day1)
            record("rem-1", TxnType.REMOVE, 6.0, 0L, day5)
            record("add-2", TxnType.ADD, 5.0, 200_00L, day10)
            record("rem-2", TxnType.REMOVE, 7.0, 0L, day15)
            // Before: add-1 fully consumed (4 by rem-1's 6? no: 6 then 4 of remaining) —
            // rem-2 takes the last 4 of add-1 and 3 of add-2.
            assertThat(txn("add-2").remainingQuantity).isEqualTo(2.0)

            // Shrink rem-1 to 1: add-1 has 9 left before rem-2; rem-2 takes 7 of add-1.
            val result = repository.updateTransaction(txn("rem-1").copy(quantity = 1.0))

            assertThat(result).isEqualTo(TransactionMutationResult.SAVED)
            assertThat(txn("add-1").remainingQuantity).isEqualTo(2.0)
            assertThat(txn("add-2").remainingQuantity).isEqualTo(5.0)
            assertThat(txn("rem-2").unitPricePaise).isEqualTo(100_00L)
            assertThat(repository.currentStock(businessId, itemId)).isEqualTo(7.0)
        }

    @Test
    fun `case 12 - deleting a missing or tombstoned transaction is a no-op`() =
        runTest {
            seedItem()
            record("add-1", TxnType.ADD, 10.0, 100_00L, day1)
            outbox.entries.clear()

            assertThat(repository.deleteTransaction("nope")).isEqualTo(TransactionMutationResult.SAVED)
            assertThat(repository.deleteTransaction("add-1")).isEqualTo(TransactionMutationResult.SAVED)
            outbox.entries.clear()
            // Second delete of the already-tombstoned row: no-op, nothing outboxed.
            assertThat(repository.deleteTransaction("add-1")).isEqualTo(TransactionMutationResult.SAVED)
            assertThat(outbox.entries).isEmpty()
        }
}
