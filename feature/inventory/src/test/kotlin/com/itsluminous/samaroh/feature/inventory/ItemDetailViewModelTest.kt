package com.itsluminous.samaroh.feature.inventory

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class ItemDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var inventory: FakeInventoryRepository
    private lateinit var viewModel: ItemDetailViewModel

    private val plate = Fixtures.masterItem(id = "item-plate", name = "Steel Plate")

    @Before
    fun setUp() {
        inventory = FakeInventoryRepository()
        inventory.masterItemsFlow.value = listOf(plate)
        inventory.linesFlow.value =
            listOf(
                CurrentInventoryLine(
                    masterItemId = "item-plate",
                    name = "Steel Plate",
                    unit = "pcs",
                    imagePath = null,
                    currentQuantity = 12.0,
                    totalValuePaise = 3_600_00L,
                    lastTransactionAt = Instant.parse("2026-08-20T09:00:00Z"),
                ),
            )
        viewModel =
            ItemDetailViewModel(
                savedStateHandle = SavedStateHandle(mapOf(ITEM_DETAIL_ID_ARG to "item-plate")),
                activeBusinessProvider = FakeActiveBusinessProvider(),
                inventoryRepository = inventory,
                overviewRepository = inventory,
            )
    }

    private fun seedTransactions(count: Int) {
        inventory.transactionsFlow.value =
            (0 until count).map { index ->
                Fixtures.inventoryTxn(
                    masterItemId = "item-plate",
                    id = "txn-$index",
                    transactionDate = Instant.parse("2026-08-20T09:00:00Z").minusSeconds(index.toLong()),
                )
            }
    }

    @Test
    fun `header exposes the item with stock and FIFO value from the aggregate`() =
        runTest {
            viewModel.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.loading).isFalse()
                assertThat(state.item?.name).isEqualTo("Steel Plate")
                assertThat(state.currentQuantity).isEqualTo(12.0)
                assertThat(state.totalValuePaise).isEqualTo(3_600_00L)
            }
        }

    @Test
    fun `first page windows the history to twenty transactions`() =
        runTest {
            seedTransactions(45)
            viewModel.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.transactions).hasSize(ITEM_DETAIL_PAGE_SIZE)
                assertThat(state.totalTransactionCount).isEqualTo(45)
                assertThat(state.hasMore).isTrue()
            }
        }

    @Test
    fun `load more reveals the next page and clears hasMore at the end`() =
        runTest {
            seedTransactions(45)
            viewModel.uiState.test {
                expectMostRecentItem()
                viewModel.loadMore()
                var state = expectMostRecentItem()
                assertThat(state.transactions).hasSize(40)
                assertThat(state.hasMore).isTrue()

                viewModel.loadMore()
                state = expectMostRecentItem()
                assertThat(state.transactions).hasSize(45)
                assertThat(state.hasMore).isFalse()
            }
        }

    @Test
    fun `fewer transactions than a page needs no load more`() =
        runTest {
            seedTransactions(3)
            viewModel.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.transactions).hasSize(3)
                assertThat(state.totalTransactionCount).isEqualTo(3)
                assertThat(state.hasMore).isFalse()
            }
        }

    @Test
    fun `missing aggregate line falls back to zero stock and value`() =
        runTest {
            inventory.linesFlow.value = emptyList()
            viewModel.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.currentQuantity).isEqualTo(0.0)
                assertThat(state.totalValuePaise).isEqualTo(0L)
            }
        }
}
