package com.itsluminous.samaroh.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * List-sort preference contract (ADR-069): the exact DataStore keys, the last-updated
 * default when unset or unrecognized, per-list independence, and value round-trips.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListSortPreferencesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val storeScope = CoroutineScope(dispatcher + Job())

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tmp.root, "settings.preferences_pb")
        }
    }
    private val prefs by lazy { ListSortPreferences(dataStore) }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `unset values default to last-updated for both lists`() =
        testScope.runTest {
            assertThat(prefs.inventoryStockSort.first()).isEqualTo(ListSortOrder.LAST_UPDATED)
            assertThat(prefs.expensesPartiesSort.first()).isEqualTo(ListSortOrder.LAST_UPDATED)
        }

    @Test
    fun `set values round-trip under the contract keys`() =
        testScope.runTest {
            prefs.setInventoryStockSort(ListSortOrder.NAME_DESC)
            prefs.setExpensesPartiesSort(ListSortOrder.NAME_ASC)

            assertThat(prefs.inventoryStockSort.first()).isEqualTo(ListSortOrder.NAME_DESC)
            assertThat(prefs.expensesPartiesSort.first()).isEqualTo(ListSortOrder.NAME_ASC)
            // Pin the raw stored strings — the cross-feature/device contract.
            dataStore.data.first().let { stored ->
                assertThat(stored[ListSortPreferences.KEY_INVENTORY_STOCK]).isEqualTo("name_desc")
                assertThat(stored[ListSortPreferences.KEY_EXPENSES_PARTIES]).isEqualTo("name_asc")
            }
        }

    @Test
    fun `the two lists' orders are independent`() =
        testScope.runTest {
            prefs.setInventoryStockSort(ListSortOrder.NAME_ASC)

            assertThat(prefs.inventoryStockSort.first()).isEqualTo(ListSortOrder.NAME_ASC)
            assertThat(prefs.expensesPartiesSort.first()).isEqualTo(ListSortOrder.LAST_UPDATED)
        }

    @Test
    fun `an unrecognized stored value falls back to last-updated`() =
        testScope.runTest {
            dataStore.edit { it[ListSortPreferences.KEY_INVENTORY_STOCK] = "sideways" }

            assertThat(prefs.inventoryStockSort.first()).isEqualTo(ListSortOrder.LAST_UPDATED)
        }
}
