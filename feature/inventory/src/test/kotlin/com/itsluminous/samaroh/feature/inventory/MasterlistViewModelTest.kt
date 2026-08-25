package com.itsluminous.samaroh.feature.inventory

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MasterlistViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var inventory: FakeInventoryRepository
    private lateinit var imageStore: FakeItemImageStore
    private lateinit var viewModel: MasterlistViewModel

    private val plate = Fixtures.masterItem(id = "item-plate", name = "Steel Plate")

    @Before
    fun setUp() {
        inventory = FakeInventoryRepository()
        inventory.masterItemsFlow.value = listOf(plate)
        imageStore = FakeItemImageStore()
        viewModel = MasterlistViewModel(FakeBusinessRepository(), inventory, inventory, imageStore, clock)
    }

    @Test
    fun `items flow exposes master items`() =
        runTest {
            viewModel.items.test {
                assertThat(expectMostRecentItem().map { it.name }).containsExactly("Steel Plate")
            }
        }

    @Test
    fun `typing three or more similar characters surfaces duplicate chips`() =
        runTest {
            viewModel.items.test { expectMostRecentItem() }
            viewModel.openEditor()
            viewModel.onNameChange("Ste")

            val editor = viewModel.editor.value
            assertThat(editor?.duplicates?.map { it.name }).contains("Steel Plate")
        }

    @Test
    fun `short queries produce no duplicate chips`() =
        runTest {
            viewModel.items.test { expectMostRecentItem() }
            viewModel.openEditor()
            viewModel.onNameChange("St")

            assertThat(viewModel.editor.value?.duplicates).isEmpty()
        }

    @Test
    fun `save requires a name`() =
        runTest {
            viewModel.openEditor()
            viewModel.saveItem()
            assertThat(viewModel.editor.value?.error).isEqualTo(MasterItemFormError.NAME_REQUIRED)
            assertThat(inventory.savedItems).isEmpty()
        }

    @Test
    fun `custom unit option requires the free-text unit`() =
        runTest {
            viewModel.openEditor()
            viewModel.onNameChange("New Item")
            viewModel.onUnitOptionChange(UnitOption.CUSTOM)
            viewModel.saveItem()
            assertThat(viewModel.editor.value?.error).isEqualTo(MasterItemFormError.UNIT_REQUIRED)
        }

    @Test
    fun `exact duplicate name is rejected case-insensitively`() =
        runTest {
            viewModel.items.test { expectMostRecentItem() }
            viewModel.openEditor()
            viewModel.onNameChange("steel plate")
            viewModel.saveItem()
            assertThat(viewModel.editor.value?.error).isEqualTo(MasterItemFormError.DUPLICATE_NAME)
            assertThat(inventory.savedItems).isEmpty()
        }

    @Test
    fun `saving a new item stores trimmed name, unit wire value and image path`() =
        runTest {
            viewModel.items.test { expectMostRecentItem() }
            viewModel.openEditor()
            viewModel.onNameChange("  Copper Pot ")
            viewModel.onUnitOptionChange(UnitOption.KG)
            viewModel.saveItem()

            val saved = inventory.savedItems.single()
            assertThat(saved.name).isEqualTo("Copper Pot")
            assertThat(saved.unit).isEqualTo("kg")
            assertThat(saved.businessId).isEqualTo(Fixtures.BUSINESS_ID)
            assertThat(viewModel.editor.value).isNull()
        }

    @Test
    fun `editing an existing item keeps its id and updates fields`() =
        runTest {
            viewModel.openEditor(plate)
            viewModel.onNameChange("Steel Plate Large")
            viewModel.onUnitOptionChange(UnitOption.CUSTOM)
            viewModel.onCustomUnitChange("dozen")
            viewModel.saveItem()

            val saved = inventory.savedItems.single()
            assertThat(saved.id).isEqualTo("item-plate")
            assertThat(saved.name).isEqualTo("Steel Plate Large")
            assertThat(saved.unit).isEqualTo("dozen")
        }

    @Test
    fun `delete request is blocked when transactions exist`() =
        runTest {
            inventory.canDeleteByItem["item-plate"] = false
            viewModel.requestDelete(plate)

            val request = viewModel.deleteRequest.value
            assertThat(request?.deletable).isFalse()

            // Confirming a blocked request must be a no-op.
            viewModel.confirmDelete()
            assertThat(inventory.deletedItemIds).isEmpty()
        }

    @Test
    fun `delete request without transactions confirms and deletes`() =
        runTest {
            inventory.canDeleteByItem["item-plate"] = true
            viewModel.requestDelete(plate)
            assertThat(viewModel.deleteRequest.value?.deletable).isTrue()

            viewModel.confirmDelete()
            assertThat(inventory.deletedItemIds).containsExactly("item-plate")
            assertThat(viewModel.deleteRequest.value).isNull()
        }
}
