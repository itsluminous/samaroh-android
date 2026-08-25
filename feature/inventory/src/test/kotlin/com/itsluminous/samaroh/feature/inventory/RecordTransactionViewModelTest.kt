package com.itsluminous.samaroh.feature.inventory

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.TxnType
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RecordTransactionViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var inventory: FakeInventoryRepository
    private lateinit var viewModel: RecordTransactionViewModel

    private val plate = Fixtures.masterItem(id = "item-plate", name = "Steel Plate")
    private val glass = Fixtures.masterItem(id = "item-glass", name = "Steel Glass")

    @Before
    fun setUp() {
        inventory = FakeInventoryRepository()
        inventory.masterItemsFlow.value = listOf(plate, glass)
        viewModel = RecordTransactionViewModel(FakeBusinessRepository(), inventory, clock)
    }

    private fun selectPlate() {
        viewModel.onItemQueryChange("Steel Pla")
        viewModel.onItemQueryDebounced("Steel Pla")
        viewModel.onItemSelected("Steel Plate")
    }

    @Test
    fun `debounced query produces fuzzy suggestions`() =
        runTest {
            viewModel.onItemQueryChange("Steel")
            viewModel.onItemQueryDebounced("Steel")

            viewModel.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.suggestions.map { it.name }).containsAtLeast("Steel Plate", "Steel Glass")
            }
        }

    @Test
    fun `selecting a suggestion loads its current stock`() =
        runTest {
            inventory.stockByItem["item-plate"] = 12.0
            selectPlate()

            viewModel.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.selectedItem?.id).isEqualTo("item-plate")
                assertThat(state.availableStock).isEqualTo(12.0)
                assertThat(state.itemQuery).isEqualTo("Steel Plate")
            }
        }

    @Test
    fun `save without an item reports item required`() =
        runTest {
            viewModel.onQuantityChange("5")
            viewModel.save()
            assertThat(viewModel.uiState.value.error).isEqualTo(TransactionFormError.ITEM_REQUIRED)
            assertThat(inventory.recordedTransactions).isEmpty()
        }

    @Test
    fun `save with invalid quantity reports quantity invalid`() =
        runTest {
            selectPlate()
            viewModel.onQuantityChange("0")
            viewModel.save()
            assertThat(viewModel.uiState.value.error).isEqualTo(TransactionFormError.QUANTITY_INVALID)
        }

    @Test
    fun `add without a unit price reports price invalid`() =
        runTest {
            selectPlate()
            viewModel.onQuantityChange("5")
            viewModel.onUnitPriceChange("")
            viewModel.save()
            assertThat(viewModel.uiState.value.error).isEqualTo(TransactionFormError.PRICE_INVALID)
        }

    @Test
    fun `remove above current stock reports insufficient stock and records nothing`() =
        runTest {
            inventory.stockByItem["item-plate"] = 3.0
            selectPlate()
            viewModel.onTypeChange(TxnType.REMOVE)
            viewModel.onQuantityChange("5")
            viewModel.save()

            assertThat(viewModel.uiState.value.error).isEqualTo(TransactionFormError.INSUFFICIENT_STOCK)
            assertThat(inventory.recordedTransactions).isEmpty()
        }

    @Test
    fun `valid add records a transaction with paise price and marks saved`() =
        runTest {
            selectPlate()
            viewModel.onQuantityChange("10")
            viewModel.onUnitPriceChange("100.50")
            viewModel.onNotesChange(" restock ")
            viewModel.save()

            val txn = inventory.recordedTransactions.single()
            assertThat(txn.transactionType).isEqualTo(TxnType.ADD)
            assertThat(txn.quantity).isEqualTo(10.0)
            assertThat(txn.unitPricePaise).isEqualTo(10_050L)
            assertThat(txn.notes).isEqualTo("restock")
            assertThat(txn.masterItemId).isEqualTo("item-plate")
            assertThat(txn.createdBy).isEqualTo(Fixtures.USER_ID)
            assertThat(viewModel.uiState.value.saved).isTrue()
        }

    @Test
    fun `remove within stock records with zero price for FIFO costing`() =
        runTest {
            inventory.stockByItem["item-plate"] = 10.0
            selectPlate()
            viewModel.onTypeChange(TxnType.REMOVE)
            viewModel.onQuantityChange("4")
            viewModel.save()

            val txn = inventory.recordedTransactions.single()
            assertThat(txn.transactionType).isEqualTo(TxnType.REMOVE)
            assertThat(txn.quantity).isEqualTo(4.0)
            assertThat(txn.unitPricePaise).isEqualTo(0L)
            assertThat(viewModel.uiState.value.saved).isTrue()
        }

    @Test
    fun `editing the query clears the previous selection`() =
        runTest {
            inventory.stockByItem["item-plate"] = 5.0
            selectPlate()
            viewModel.onItemQueryChange("Steel Gla")

            val state = viewModel.uiState.value
            assertThat(state.selectedItem).isNull()
            assertThat(state.availableStock).isNull()
        }
}
