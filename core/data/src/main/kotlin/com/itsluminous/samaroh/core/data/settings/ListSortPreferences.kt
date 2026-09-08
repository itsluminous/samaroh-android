package com.itsluminous.samaroh.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a feature list is ordered (ADR-069). Presentation-level: sorting is applied by
 * the list ViewModels over the DAO's stable name ordering — no query contract changes.
 */
enum class ListSortOrder(
    val storageValue: String,
) {
    /** Most recent activity first (default): last entry (parties) / last transaction (items). */
    LAST_UPDATED("last_updated"),

    /** Alphabetical by name, ascending (case-insensitive). */
    NAME_ASC("name_asc"),

    /** Alphabetical by name, descending (case-insensitive). */
    NAME_DESC("name_desc"),

    ;

    companion object {
        /** Stored string → order; unknown/unset falls back to [LAST_UPDATED]. */
        fun fromStorage(value: String?): ListSortOrder = entries.firstOrNull { it.storageValue == value } ?: LAST_UPDATED
    }
}

/**
 * Per-device, per-list SORT ORDER preferences (ADR-069) in the shared settings
 * DataStore. Each sortable list has its own key so the Inventory stock list and the
 * Expenses party list remember their orders independently. Written by the lists' own
 * sort menus; read by their ViewModels. Unset or unrecognized stored values fall back
 * to [ListSortOrder.LAST_UPDATED] (newest activity first) — the product default.
 */
@Singleton
class ListSortPreferences
    @Inject
    constructor(
        @SettingsDataStore private val dataStore: DataStore<Preferences>,
    ) {
        /** Inventory tab → Current Inventory (stock) list order. */
        val inventoryStockSort: Flow<ListSortOrder> =
            dataStore.data.map { prefs -> ListSortOrder.fromStorage(prefs[KEY_INVENTORY_STOCK]) }

        /** Expenses tab → home party list order. */
        val expensesPartiesSort: Flow<ListSortOrder> =
            dataStore.data.map { prefs -> ListSortOrder.fromStorage(prefs[KEY_EXPENSES_PARTIES]) }

        suspend fun setInventoryStockSort(order: ListSortOrder) {
            dataStore.edit { it[KEY_INVENTORY_STOCK] = order.storageValue }
        }

        suspend fun setExpensesPartiesSort(order: ListSortOrder) {
            dataStore.edit { it[KEY_EXPENSES_PARTIES] = order.storageValue }
        }

        companion object {
            val KEY_INVENTORY_STOCK = stringPreferencesKey("sort_order_inventory_stock")
            val KEY_EXPENSES_PARTIES = stringPreferencesKey("sort_order_expenses_parties")
        }
    }
