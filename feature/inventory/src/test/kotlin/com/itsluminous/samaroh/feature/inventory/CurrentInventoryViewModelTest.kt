package com.itsluminous.samaroh.feature.inventory

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

class CurrentInventoryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var inventory: FakeInventoryRepository
    private lateinit var viewModel: CurrentInventoryViewModel

    private fun line(
        id: String,
        name: String,
        quantity: Double = 5.0,
        valuePaise: Long = 500_00L,
    ) = CurrentInventoryLine(
        masterItemId = id,
        name = name,
        unit = "pcs",
        imagePath = null,
        currentQuantity = quantity,
        totalValuePaise = valuePaise,
        lastTransactionAt = Instant.parse("2026-08-20T09:00:00Z"),
    )

    @Before
    fun setUp() {
        inventory = FakeInventoryRepository()
        inventory.linesFlow.value =
            listOf(
                line("item-plate", "Steel Plate", quantity = 7.0, valuePaise = 700_00L),
                line("item-chair", "Plastic Chair"),
            )
        viewModel = CurrentInventoryViewModel(FakeActiveBusinessProvider(Fixtures.business()), inventory, ownerModeInventorySession())
    }

    @Test
    fun `ui state exposes all lines with stock and value`() =
        runTest {
            viewModel.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.loading).isFalse()
                assertThat(state.lines.map { it.name }).containsExactly("Steel Plate", "Plastic Chair")
                val plate = state.lines.first { it.masterItemId == "item-plate" }
                assertThat(plate.currentQuantity).isEqualTo(7.0)
                assertThat(plate.totalValuePaise).isEqualTo(700_00L)
            }
        }

    @Test
    fun `search filters case-insensitively by name`() =
        runTest {
            viewModel.uiState.test {
                expectMostRecentItem()
                viewModel.onSearchQueryChange("steel")
                val state = expectMostRecentItem()
                assertThat(state.lines.map { it.name }).containsExactly("Steel Plate")
                assertThat(state.noSearchResults).isFalse()
            }
        }

    @Test
    fun `no-results flag is set only when a query filters everything out`() =
        runTest {
            viewModel.uiState.test {
                expectMostRecentItem()
                viewModel.onSearchQueryChange("missing thing")
                val state = expectMostRecentItem()
                assertThat(state.lines).isEmpty()
                assertThat(state.noSearchResults).isTrue()
            }
        }

    @Test
    fun `clearing the search restores the full list`() =
        runTest {
            viewModel.uiState.test {
                expectMostRecentItem()
                viewModel.onSearchQueryChange("steel")
                expectMostRecentItem()
                viewModel.onSearchQueryChange("")
                val state = expectMostRecentItem()
                assertThat(state.lines).hasSize(2)
                assertThat(state.noSearchResults).isFalse()
            }
        }

    @Test
    fun `zero-quantity items appear after in-stock items, each group alphabetical`() =
        runTest {
            // The repository emits name-sorted rows (DAO ORDER BY name).
            inventory.linesFlow.value =
                listOf(
                    line("item-bowl", "Bowl", quantity = 0.0, valuePaise = 0L),
                    line("item-chair", "Plastic Chair", quantity = 0.0, valuePaise = 0L),
                    line("item-spoon", "Spoon", quantity = 2.0),
                    line("item-plate", "Steel Plate", quantity = 7.0),
                )
            viewModel.uiState.test {
                val state = expectMostRecentItem()
                // In-stock first (alphabetical), zero-stock appended (alphabetical).
                assertThat(state.lines.map { it.name })
                    .containsExactly("Spoon", "Steel Plate", "Bowl", "Plastic Chair")
                    .inOrder()
                val bowl = state.lines.first { it.masterItemId == "item-bowl" }
                assertThat(bowl.currentQuantity).isEqualTo(0.0)
                assertThat(bowl.totalValuePaise).isEqualTo(0L)
            }
        }

    @Test
    fun `search matches zero-stock items too`() =
        runTest {
            inventory.linesFlow.value =
                listOf(
                    line("item-bowl", "Bowl", quantity = 0.0, valuePaise = 0L),
                    line("item-plate", "Steel Plate", quantity = 7.0),
                )
            viewModel.uiState.test {
                expectMostRecentItem()
                viewModel.onSearchQueryChange("bowl")
                val state = expectMostRecentItem()
                assertThat(state.lines.map { it.name }).containsExactly("Bowl")
                assertThat(state.noSearchResults).isFalse()
            }
        }

    @Test
    fun `all items at zero still lists every item`() =
        runTest {
            inventory.linesFlow.value =
                listOf(
                    line("item-plate", "Steel Plate", quantity = 0.0, valuePaise = 0L),
                    line("item-chair", "Plastic Chair", quantity = 0.0, valuePaise = 0L),
                )
            viewModel.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.lines.map { it.name }).containsExactly("Steel Plate", "Plastic Chair")
                assertThat(state.noSearchResults).isFalse()
            }
        }

    @Test
    fun `no items at all leaves the list empty`() =
        runTest {
            inventory.linesFlow.value = emptyList()
            viewModel.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.lines).isEmpty()
                assertThat(state.noSearchResults).isFalse()
            }
        }
}
