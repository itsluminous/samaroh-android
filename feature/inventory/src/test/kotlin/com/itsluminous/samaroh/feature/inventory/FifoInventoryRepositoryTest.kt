package com.itsluminous.samaroh.feature.inventory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.repository.FifoInventoryRepository
import com.itsluminous.samaroh.core.data.repository.RoomInventoryRepository
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
private class RecordingOutboxWriter : OutboxWriter {
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

@RunWith(RobolectricTestRunner::class)
class FifoInventoryRepositoryTest {
    private lateinit var db: SamarohDatabase
    private lateinit var outbox: RecordingOutboxWriter
    private lateinit var repository: FifoInventoryRepository

    private val clock = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC)
    private val businessId = Fixtures.BUSINESS_ID
    private val itemId = "item-1"

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        outbox = RecordingOutboxWriter()
        val room = RoomInventoryRepository(db.masterItemDao(), db.inventoryTransactionDao(), outbox, clock)
        repository = FifoInventoryRepository(room, db.inventoryTransactionDao(), outbox, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedItem(
        id: String = itemId,
        name: String = "fixture-item",
    ) = repository.saveMasterItem(Fixtures.masterItem(id = id, name = name))

    private fun addTxn(
        quantity: Double,
        unitPricePaise: Long,
        date: Instant,
        id: String =
            java.util.UUID
                .randomUUID()
                .toString(),
    ): InventoryTransaction =
        Fixtures.inventoryTxn(
            masterItemId = itemId,
            id = id,
            type = TxnType.ADD,
            quantity = quantity,
            unitPricePaise = unitPricePaise,
            // The repository owns remainder bookkeeping; deliberately pass a wrong value.
            remainingQuantity = 0.0,
            transactionDate = date,
        )

    private fun removeTxn(
        quantity: Double,
        date: Instant = Instant.parse("2026-08-20T09:00:00Z"),
    ): InventoryTransaction =
        Fixtures.inventoryTxn(
            masterItemId = itemId,
            type = TxnType.REMOVE,
            quantity = quantity,
            unitPricePaise = 0L,
            remainingQuantity = 0.0,
            transactionDate = date,
        )

    @Test
    fun `add opens a lot with full remaining quantity`() =
        runTest {
            seedItem()
            repository.recordTransaction(addTxn(10.0, 100_00L, Instant.parse("2026-08-01T09:00:00Z")))

            val lots = repository.openAddLotsFifo(businessId, itemId)
            assertThat(lots).hasSize(1)
            assertThat(lots.first().remainingQuantity).isEqualTo(10.0)
        }

    @Test
    fun `remove consumes the oldest lot first`() =
        runTest {
            seedItem()
            val oldId = "lot-old"
            val newId = "lot-new"
            repository.recordTransaction(addTxn(10.0, 100_00L, Instant.parse("2026-08-01T09:00:00Z"), id = oldId))
            repository.recordTransaction(addTxn(5.0, 200_00L, Instant.parse("2026-08-10T09:00:00Z"), id = newId))

            repository.recordTransaction(removeTxn(4.0))

            val lots = repository.openAddLotsFifo(businessId, itemId)
            assertThat(lots.map { it.id }).containsExactly(oldId, newId).inOrder()
            assertThat(lots.first { it.id == oldId }.remainingQuantity).isEqualTo(6.0)
            assertThat(lots.first { it.id == newId }.remainingQuantity).isEqualTo(5.0)
        }

    @Test
    fun `remove spanning lots exhausts the oldest and partially consumes the next`() =
        runTest {
            seedItem()
            val oldId = "lot-old"
            val newId = "lot-new"
            repository.recordTransaction(addTxn(10.0, 100_00L, Instant.parse("2026-08-01T09:00:00Z"), id = oldId))
            repository.recordTransaction(addTxn(5.0, 200_00L, Instant.parse("2026-08-10T09:00:00Z"), id = newId))

            repository.recordTransaction(removeTxn(12.0))

            val lots = repository.openAddLotsFifo(businessId, itemId)
            assertThat(lots.map { it.id }).containsExactly(newId)
            assertThat(lots.first().remainingQuantity).isEqualTo(3.0)
            assertThat(repository.currentStock(businessId, itemId)).isEqualTo(3.0)
        }

    @Test
    fun `remove records the FIFO weighted-average unit cost in paise`() =
        runTest {
            seedItem()
            repository.recordTransaction(addTxn(10.0, 100_00L, Instant.parse("2026-08-01T09:00:00Z")))
            repository.recordTransaction(addTxn(5.0, 200_00L, Instant.parse("2026-08-10T09:00:00Z")))

            repository.recordTransaction(removeTxn(12.0))

            val remove =
                repository
                    .transactionsForItem(businessId, itemId)
                    .first()
                    .first { it.transactionType == TxnType.REMOVE }
            // 10 × 10000 + 2 × 20000 = 140000 paise over 12 units → 11666.67 → 11667.
            assertThat(remove.unitPricePaise).isEqualTo(11_667L)
            assertThat(remove.remainingQuantity).isEqualTo(0.0)
        }

    @Test
    fun `current inventory reports stock and FIFO value after consumption`() =
        runTest {
            seedItem()
            repository.recordTransaction(addTxn(10.0, 100_00L, Instant.parse("2026-08-01T09:00:00Z")))
            repository.recordTransaction(removeTxn(3.0))

            val line = repository.currentInventory(businessId).first().single()
            // Acceptance criterion 8: add 10 @ ₹100, remove 3 → stock 7, value ₹700.
            assertThat(line.currentQuantity).isEqualTo(7.0)
            assertThat(line.totalValuePaise).isEqualTo(700_00L)
            assertThat(line.lastTransactionAt).isNotNull()
        }

    @Test
    fun `current inventory values multiple open lots at their own prices`() =
        runTest {
            seedItem()
            repository.recordTransaction(addTxn(10.0, 100_00L, Instant.parse("2026-08-01T09:00:00Z")))
            repository.recordTransaction(addTxn(5.0, 200_00L, Instant.parse("2026-08-10T09:00:00Z")))
            repository.recordTransaction(removeTxn(12.0))

            val line = repository.currentInventory(businessId).first().single()
            assertThat(line.currentQuantity).isEqualTo(3.0)
            // 3 remaining in the ₹200 lot only.
            assertThat(line.totalValuePaise).isEqualTo(600_00L)
        }

    @Test
    fun `remove larger than stock is rejected and changes nothing`() =
        runTest {
            seedItem()
            repository.recordTransaction(addTxn(5.0, 100_00L, Instant.parse("2026-08-01T09:00:00Z")))

            val result = runCatching { repository.recordTransaction(removeTxn(6.0)) }
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)

            assertThat(repository.currentStock(businessId, itemId)).isEqualTo(5.0)
            assertThat(repository.openAddLotsFifo(businessId, itemId).single().remainingQuantity).isEqualTo(5.0)
        }

    @Test
    fun `fractional quantities round to three decimals during consumption`() =
        runTest {
            seedItem()
            repository.recordTransaction(addTxn(1.0, 100_00L, Instant.parse("2026-08-01T09:00:00Z")))

            repository.recordTransaction(removeTxn(0.333))

            val lot = repository.openAddLotsFifo(businessId, itemId).single()
            assertThat(lot.remainingQuantity).isEqualTo(0.667)
        }

    @Test
    fun `consumed lots are re-enqueued to the outbox for sync`() =
        runTest {
            seedItem()
            val lotId = "lot-old"
            repository.recordTransaction(addTxn(10.0, 100_00L, Instant.parse("2026-08-01T09:00:00Z"), id = lotId))
            outbox.entries.clear()

            repository.recordTransaction(removeTxn(4.0))

            val txnUpserts = outbox.entries.filter { it.entityType == "inventory_transactions" }
            // One upsert for the touched lot, one for the remove row itself.
            assertThat(txnUpserts).hasSize(2)
            assertThat(txnUpserts.first().entityId).isEqualTo(lotId)
            assertThat(txnUpserts.first().operation).isEqualTo(OutboxOperation.UPSERT)
            assertThat(txnUpserts.first().payloadJson).contains("\"remaining_quantity\":6.0")
        }

    @Test
    fun `master item with transactions cannot be deleted even after tombstoning them`() =
        runTest {
            seedItem()
            val txn = addTxn(10.0, 100_00L, Instant.parse("2026-08-01T09:00:00Z"), id = "lot-1")
            repository.recordTransaction(txn)

            assertThat(repository.canDeleteMasterItem(itemId)).isFalse()

            db.inventoryTransactionDao().tombstone("lot-1", clock.instant())
            assertThat(repository.canDeleteMasterItem(itemId)).isFalse()
        }

    @Test
    fun `master item without transactions is deletable and delete tombstones it`() =
        runTest {
            seedItem()
            assertThat(repository.canDeleteMasterItem(itemId)).isTrue()

            repository.deleteMasterItem(itemId)

            assertThat(repository.masterItems(businessId).first()).isEmpty()
            assertThat(repository.currentInventory(businessId).first()).isEmpty()
        }

    @Test
    fun `recordTransactionForValue returns quantity times unit price for an add`() =
        runTest {
            seedItem()

            val value =
                repository.recordTransactionForValue(
                    addTxn(10.0, 100_50L, Instant.parse("2026-08-01T09:00:00Z")),
                )

            assertThat(value).isEqualTo(100_500L)
        }

    @Test
    fun `recordTransactionForValue returns the FIFO cost of a remove across lots`() =
        runTest {
            seedItem()
            repository.recordTransaction(addTxn(10.0, 100_00L, Instant.parse("2026-08-01T09:00:00Z")))
            repository.recordTransaction(addTxn(5.0, 200_00L, Instant.parse("2026-08-10T09:00:00Z")))

            // 10 from the ₹100 lot + 2 from the ₹200 lot = ₹1,400 = 140000 paise.
            val cost = repository.recordTransactionForValue(removeTxn(12.0))

            assertThat(cost).isEqualTo(140_000L)
        }
}
