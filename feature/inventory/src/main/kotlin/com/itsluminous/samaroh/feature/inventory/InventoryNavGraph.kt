package com.itsluminous.samaroh.feature.inventory

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.samaroh.feature.inventory.ui.CurrentInventoryScreen
import com.itsluminous.samaroh.feature.inventory.ui.MasterlistScreen

/** Route of the Inventory tab's start destination. */
const val INVENTORY_ROUTE = "inventory"

/** The two Inventory screens, toggled via the contextual top-bar icon (§4.3). */
private enum class InventoryScreen {
    STOCK,
    MASTERLIST,
}

/**
 * Inventory feature graph (§4.3): the Current Inventory (stock) screen and the
 * Masterlist screen live under one destination and swap via the contextual top-bar
 * toggle — mirroring the reference navigation pattern without leaving the tab.
 */
fun NavGraphBuilder.inventoryGraph() {
    composable(INVENTORY_ROUTE) {
        InventoryRoute()
    }
}

@Composable
private fun InventoryRoute() {
    var screen by rememberSaveable { mutableStateOf(InventoryScreen.STOCK) }
    when (screen) {
        InventoryScreen.STOCK ->
            CurrentInventoryScreen(onOpenMasterlist = { screen = InventoryScreen.MASTERLIST })
        InventoryScreen.MASTERLIST -> {
            // System back returns to the stock screen instead of leaving the tab.
            BackHandler { screen = InventoryScreen.STOCK }
            MasterlistScreen(onOpenStock = { screen = InventoryScreen.STOCK })
        }
    }
}
