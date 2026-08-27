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
        viewModel = RecordTransactionViewModel(FakeBusinessRepository(), FakeActiveBusinessProvider(), inventory, inventory, clock)
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
            assertThat(viewModel.uiState.value.saved).isNotNull()
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
            assertThat(viewModel.uiState.value.saved).isNotNull()
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

    @Test
    fun `blank query lists every item sorted by name`() =
        runTest {
            viewModel.onItemQueryDebounced("")

            val state = viewModel.uiState.value
            assertThat(state.suggestions.map { it.name }).containsExactly("Steel Glass", "Steel Plate").inOrder()
        }

    @Test
    fun `short query substring-filters instead of returning nothing`() =
        runTest {
            // "gl" is below FuzzyMatcher.MIN_QUERY_LENGTH — the old behavior showed nothing.
            viewModel.onItemQueryDebounced("gl")

            val state = viewModel.uiState.value
            assertThat(state.suggestions.map { it.name }).containsExactly("Steel Glass")
        }

    @Test
    fun `remove mode offers only in-stock items and exposes the stock map`() =
        runTest {
            inventory.linesFlow.value =
                listOf(
                    inventoryLine(id = "item-plate", name = "Steel Plate", quantity = 4.0),
                    inventoryLine(id = "item-glass", name = "Steel Glass", quantity = 0.0),
                )
            viewModel.onTypeChange(TxnType.REMOVE)
            viewModel.onItemQueryDebounced("")

            val state = viewModel.uiState.value
            assertThat(state.suggestions.map { it.id }).containsExactly("item-plate")
            assertThat(state.stockByItemId["item-plate"]).isEqualTo(4.0)
        }

    @Test
    fun `remove quantity above stock flags the error live while typing`() =
        runTest {
            inventory.stockByItem["item-plate"] = 3.0
            selectPlate()
            viewModel.onTypeChange(TxnType.REMOVE)

            viewModel.onQuantityChange("5")
            assertThat(viewModel.uiState.value.error).isEqualTo(TransactionFormError.INSUFFICIENT_STOCK)

            viewModel.onQuantityChange("2")
            assertThat(viewModel.uiState.value.error).isNull()
        }

    @Test
    fun `saved add carries quantity times unit price`() =
        runTest {
            selectPlate()
            viewModel.onQuantityChange("10")
            viewModel.onUnitPriceChange("100.50")
            viewModel.save()

            val saved = viewModel.uiState.value.saved
            assertThat(saved?.type).isEqualTo(TxnType.ADD)
            assertThat(saved?.totalValuePaise).isEqualTo(100_500L)
        }

    @Test
    fun `saved remove carries the FIFO cost from the repository`() =
        runTest {
            inventory.stockByItem["item-plate"] = 10.0
            inventory.removeCostPaise = 42_00L
            selectPlate()
            viewModel.onTypeChange(TxnType.REMOVE)
            viewModel.onQuantityChange("4")
            viewModel.save()

            val saved = viewModel.uiState.value.saved
            assertThat(saved?.type).isEqualTo(TxnType.REMOVE)
            assertThat(saved?.totalValuePaise).isEqualTo(42_00L)
        }

    @Test
    fun `preselectItem selects the item and loads its stock`() =
        runTest {
            inventory.stockByItem["item-glass"] = 6.0
            viewModel.preselectItem("item-glass", TxnType.REMOVE)

            val state = viewModel.uiState.value
            assertThat(state.selectedItem?.id).isEqualTo("item-glass")
            assertThat(state.itemQuery).isEqualTo("Steel Glass")
            assertThat(state.type).isEqualTo(TxnType.REMOVE)
            assertThat(state.availableStock).isEqualTo(6.0)
        }

    @Test
    fun `consumeSaved resets the form for the next opening`() =
        runTest {
            selectPlate()
            viewModel.onQuantityChange("1")
            viewModel.onUnitPriceChange("10")
            viewModel.save()
            assertThat(viewModel.uiState.value.saved).isNotNull()

            viewModel.consumeSaved()
            assertThat(viewModel.uiState.value).isEqualTo(RecordTransactionUiState())
        }

    private fun inventoryLine(
        id: String,
        name: String,
        quantity: Double,
    ) = com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine(
        masterItemId = id,
        name = name,
        unit = "pcs",
        imagePath = null,
        currentQuantity = quantity,
        totalValuePaise = 0L,
        lastTransactionAt = null,
    )
}
