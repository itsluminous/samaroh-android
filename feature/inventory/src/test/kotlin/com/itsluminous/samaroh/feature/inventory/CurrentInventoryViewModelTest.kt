package com.itsluminous.samaroh.feature.inventory

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine
import com.itsluminous.samaroh.core.data.settings.ListSortOrder
import com.itsluminous.samaroh.core.data.settings.ListSortPreferences
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class CurrentInventoryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(mainDispatcherRule.dispatcher + Job())
    private val sortPreferences by lazy {
        ListSortPreferences(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tmp.root, "settings.preferences_pb")
            },
        )
    }

    private lateinit var inventory: FakeInventoryRepository
    private lateinit var viewModel: CurrentInventoryViewModel

    private fun line(
        id: String,
        name: String,
        quantity: Double = 5.0,
        valuePaise: Long = 500_00L,
        lastTransactionAt: Instant? = Instant.parse("2026-08-20T09:00:00Z"),
    ) = CurrentInventoryLine(
        masterItemId = id,
        name = name,
        unit = "pcs",
        imagePath = null,
        driveImageId = null,
        currentQuantity = quantity,
        totalValuePaise = valuePaise,
        lastTransactionAt = lastTransactionAt,
    )

    @Before
    fun setUp() {
        inventory = FakeInventoryRepository()
        inventory.linesFlow.value =
            listOf(
                line("item-plate", "Steel Plate", quantity = 7.0, valuePaise = 700_00L),
                line("item-chair", "Plastic Chair"),
            )
        viewModel =
            CurrentInventoryViewModel(
                FakeActiveBusinessProvider(Fixtures.business()),
                inventory,
                ownerModeInventorySession(),
                sortPreferences,
            )
    }

    @After
    fun tearDown() {
        storeScope.cancel()
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

    @Test
    fun `default last-updated sorts newest activity first with never-moved items last`() =
        runTest {
            inventory.linesFlow.value =
                listOf(
                    line("item-bowl", "Bowl", lastTransactionAt = Instant.parse("2026-09-01T10:00:00Z")),
                    line("item-chair", "Plastic Chair", lastTransactionAt = null),
                    line("item-plate", "Steel Plate", lastTransactionAt = Instant.parse("2026-09-05T10:00:00Z")),
                    line("item-spoon", "Spoon", lastTransactionAt = Instant.parse("2026-08-01T10:00:00Z")),
                )
            viewModel.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.sortOrder).isEqualTo(ListSortOrder.LAST_UPDATED)
                assertThat(state.lines.map { it.name })
                    .containsExactly("Steel Plate", "Bowl", "Spoon", "Plastic Chair")
                    .inOrder()
            }
        }

    @Test
    fun `name ascending and descending reorder the list and expose the selection`() =
        runTest {
            inventory.linesFlow.value =
                listOf(
                    line("item-plate", "Steel Plate", lastTransactionAt = Instant.parse("2026-09-05T10:00:00Z")),
                    line("item-bowl", "bowl", lastTransactionAt = Instant.parse("2026-09-01T10:00:00Z")),
                    line("item-spoon", "Spoon", lastTransactionAt = Instant.parse("2026-08-01T10:00:00Z")),
                )
            viewModel.uiState.test {
                expectMostRecentItem()

                viewModel.onSortOrderChange(ListSortOrder.NAME_ASC)
                val ascending = expectMostRecentItem()
                assertThat(ascending.sortOrder).isEqualTo(ListSortOrder.NAME_ASC)
                // Case-insensitive: lowercase "bowl" still leads.
                assertThat(ascending.lines.map { it.name })
                    .containsExactly("bowl", "Spoon", "Steel Plate")
                    .inOrder()

                viewModel.onSortOrderChange(ListSortOrder.NAME_DESC)
                val descending = expectMostRecentItem()
                assertThat(descending.sortOrder).isEqualTo(ListSortOrder.NAME_DESC)
                assertThat(descending.lines.map { it.name })
                    .containsExactly("Steel Plate", "Spoon", "bowl")
                    .inOrder()
            }
        }

    @Test
    fun `zero-stock items stay at the end under every sort, same order within each group`() =
        runTest {
            inventory.linesFlow.value =
                listOf(
                    line("item-bowl", "Bowl", quantity = 0.0, valuePaise = 0L, lastTransactionAt = Instant.parse("2026-09-06T10:00:00Z")),
                    line("item-chair", "Plastic Chair", quantity = 3.0, lastTransactionAt = Instant.parse("2026-09-01T10:00:00Z")),
                    line("item-plate", "Steel Plate", quantity = 7.0, lastTransactionAt = Instant.parse("2026-09-05T10:00:00Z")),
                    line("item-spoon", "Spoon", quantity = 0.0, valuePaise = 0L, lastTransactionAt = null),
                )
            viewModel.uiState.test {
                // LAST_UPDATED (default): zero-stock Bowl is the NEWEST row overall but
                // still renders after every in-stock row (ADR-057 partition wins).
                val lastUpdated = expectMostRecentItem()
                assertThat(lastUpdated.lines.map { it.name })
                    .containsExactly("Steel Plate", "Plastic Chair", "Bowl", "Spoon")
                    .inOrder()

                viewModel.onSortOrderChange(ListSortOrder.NAME_ASC)
                val ascending = expectMostRecentItem()
                assertThat(ascending.lines.map { it.name })
                    .containsExactly("Plastic Chair", "Steel Plate", "Bowl", "Spoon")
                    .inOrder()

                viewModel.onSortOrderChange(ListSortOrder.NAME_DESC)
                val descending = expectMostRecentItem()
                assertThat(descending.lines.map { it.name })
                    .containsExactly("Steel Plate", "Plastic Chair", "Spoon", "Bowl")
                    .inOrder()
            }
        }

    @Test
    fun `search filters first, then the chosen sort applies within the results`() =
        runTest {
            inventory.linesFlow.value =
                listOf(
                    line("item-plate", "Steel Plate", lastTransactionAt = Instant.parse("2026-09-05T10:00:00Z")),
                    line("item-spoon", "Steel Spoon", lastTransactionAt = Instant.parse("2026-09-06T10:00:00Z")),
                    line("item-bowl", "Bowl", lastTransactionAt = Instant.parse("2026-09-07T10:00:00Z")),
                )
            viewModel.uiState.test {
                expectMostRecentItem()
                viewModel.onSortOrderChange(ListSortOrder.NAME_DESC)
                expectMostRecentItem()
                viewModel.onSearchQueryChange("steel")
                val state = expectMostRecentItem()
                assertThat(state.lines.map { it.name })
                    .containsExactly("Steel Spoon", "Steel Plate")
                    .inOrder()
            }
        }

    @Test
    fun `chosen sort persists - a fresh ViewModel over the same prefs restores it`() =
        runTest {
            viewModel.uiState.test {
                expectMostRecentItem()
                viewModel.onSortOrderChange(ListSortOrder.NAME_DESC)
                expectMostRecentItem()
            }

            val recreated =
                CurrentInventoryViewModel(
                    FakeActiveBusinessProvider(Fixtures.business()),
                    inventory,
                    ownerModeInventorySession(),
                    sortPreferences,
                )
            recreated.uiState.test {
                assertThat(expectMostRecentItem().sortOrder).isEqualTo(ListSortOrder.NAME_DESC)
            }
        }
}
