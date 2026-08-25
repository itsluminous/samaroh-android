package com.itsluminous.samaroh.core.database

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class OutboxDaoTest {
    private lateinit var db: SamarohDatabase

    @Before
    fun setUp() {
        db = testDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `nextBatch returns entries in strict enqueue order`() =
        runTest {
            val dao = db.outboxDao()
            dao.enqueue(outboxFixture(entityType = "bookings", entityId = "e-1"))
            dao.enqueue(outboxFixture(entityType = "booking_payments", entityId = "e-2"))
            dao.enqueue(outboxFixture(entityType = "bookings", entityId = "e-3", operation = "delete"))

            val batch = dao.nextBatch()

            assertThat(batch.map { it.entityId }).containsExactly("e-1", "e-2", "e-3").inOrder()
            assertThat(batch.map { it.id }).isInOrder()
        }

    @Test
    fun `remove after successful push shrinks the queue head`() =
        runTest {
            val dao = db.outboxDao()
            val first = dao.enqueue(outboxFixture(entityId = "e-1"))
            dao.enqueue(outboxFixture(entityId = "e-2"))

            dao.remove(first)

            assertThat(dao.nextBatch().map { it.entityId }).containsExactly("e-2")
            assertThat(dao.pendingCount().first()).isEqualTo(1)
        }

    @Test
    fun `recordFailure increments attempts and keeps FIFO position`() =
        runTest {
            val dao = db.outboxDao()
            val id = dao.enqueue(outboxFixture(entityId = "e-1"))
            dao.enqueue(outboxFixture(entityId = "e-2"))

            dao.recordFailure(id, "RLS rejected")
            dao.recordFailure(id, "RLS rejected again")

            val head = dao.nextBatch().first()
            assertThat(head.entityId).isEqualTo("e-1")
            assertThat(head.attemptCount).isEqualTo(2)
            assertThat(head.lastError).isEqualTo("RLS rejected again")
        }
}

@RunWith(RobolectricTestRunner::class)
class InventoryFifoQueryTest {
    private lateinit var db: SamarohDatabase
    private val itemId = "item-1"

    @Before
    fun setUp() {
        db = testDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `open lots come back oldest first with exhausted, removed and tombstoned lots excluded`() =
        runTest {
            val dao = db.inventoryTransactionDao()
            val oldest = inventoryTxnFixture(itemId, quantity = 10.0, transactionDate = Instant.parse("2026-07-01T09:00:00Z"))
            val middle = inventoryTxnFixture(itemId, quantity = 5.0, transactionDate = Instant.parse("2026-07-10T09:00:00Z"))
            val exhausted =
                inventoryTxnFixture(
                    itemId,
                    quantity = 4.0,
                    remainingQuantity = 0.0,
                    transactionDate = Instant.parse("2026-06-01T09:00:00Z"),
                )
            val removal =
                inventoryTxnFixture(
                    itemId,
                    type = com.itsluminous.samaroh.core.model.TxnType.REMOVE,
                    quantity = 3.0,
                    transactionDate = Instant.parse("2026-07-15T09:00:00Z"),
                )
            val tombstoned = inventoryTxnFixture(itemId, quantity = 7.0, transactionDate = Instant.parse("2026-06-15T09:00:00Z"))
            listOf(oldest, middle, exhausted, removal, tombstoned).forEach { dao.upsert(it) }
            dao.tombstone(tombstoned.id, Instant.parse("2026-07-20T09:00:00Z"))

            val lots = dao.openAddLotsFifo(TEST_BUSINESS_ID, itemId)

            assertThat(lots.map { it.id }).containsExactly(oldest.id, middle.id).inOrder()
        }

    @Test
    fun `partial consumption keeps the lot open with reduced remainder`() =
        runTest {
            val dao = db.inventoryTransactionDao()
            val lot = inventoryTxnFixture(itemId, quantity = 10.0)
            dao.upsert(lot)

            dao.updateRemainingQuantity(lot.id, 7.0, Instant.parse("2026-08-02T09:00:00Z"))

            val open = dao.openAddLotsFifo(TEST_BUSINESS_ID, itemId)
            assertThat(open).hasSize(1)
            assertThat(open.first().remainingQuantity).isEqualTo(7.0)
        }

    @Test
    fun `current stock is adds minus removes over live rows`() =
        runTest {
            val dao = db.inventoryTransactionDao()
            dao.upsert(inventoryTxnFixture(itemId, quantity = 10.0))
            dao.upsert(
                inventoryTxnFixture(
                    itemId,
                    type = com.itsluminous.samaroh.core.model.TxnType.REMOVE,
                    quantity = 3.0,
                    transactionDate = Instant.parse("2026-08-05T09:00:00Z"),
                ),
            )

            assertThat(dao.currentStock(TEST_BUSINESS_ID, itemId)).isEqualTo(7.0)
        }
}

@RunWith(RobolectricTestRunner::class)
class PartyBalanceQueryTest {
    private lateinit var db: SamarohDatabase

    @Before
    fun setUp() {
        db = testDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `net balance is paid minus received per party`() =
        runTest {
            val now = Instant.parse("2026-08-01T09:00:00Z")
            val party =
                com.itsluminous.samaroh.core.database.entity.PartyEntity(
                    id = "p-1",
                    businessId = TEST_BUSINESS_ID,
                    name = "Decorator",
                    createdAt = now,
                    updatedAt = now,
                )
            db.partyDao().upsert(party)
            val expense = { id: String, direction: com.itsluminous.samaroh.core.model.ExpenseDirection, amount: Long ->
                com.itsluminous.samaroh.core.database.entity.ExpenseEntity(
                    id = id,
                    businessId = TEST_BUSINESS_ID,
                    partyId = "p-1",
                    direction = direction,
                    amountPaise = amount,
                    expenseDate = java.time.LocalDate.of(2026, 8, 1),
                    createdBy = TEST_USER_ID,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            db.expenseDao().upsert(expense("e-1", com.itsluminous.samaroh.core.model.ExpenseDirection.PAID, 5_000_00L))
            db.expenseDao().upsert(expense("e-2", com.itsluminous.samaroh.core.model.ExpenseDirection.RECEIVED, 1_200_00L))
            db.expenseDao().upsert(expense("e-3", com.itsluminous.samaroh.core.model.ExpenseDirection.PAID, 800_00L))
            // Tombstoned expense must not count.
            db.expenseDao().upsert(expense("e-4", com.itsluminous.samaroh.core.model.ExpenseDirection.PAID, 99_999_00L))
            db.expenseDao().tombstone("e-4", now)

            val rows = db.partyDao().partiesWithBalance(TEST_BUSINESS_ID).first()

            assertThat(rows).hasSize(1)
            assertThat(rows.first().netBalancePaise).isEqualTo(5_000_00L - 1_200_00L + 800_00L)
        }

    @Test
    fun `party with no expenses has zero balance`() =
        runTest {
            val now = Instant.parse("2026-08-01T09:00:00Z")
            db.partyDao().upsert(
                com.itsluminous.samaroh.core.database.entity.PartyEntity(
                    id = "p-2",
                    businessId = TEST_BUSINESS_ID,
                    name = "Caterer",
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            val rows = db.partyDao().partiesWithBalance(TEST_BUSINESS_ID).first()
            assertThat(rows.first().netBalancePaise).isEqualTo(0L)
        }
}
