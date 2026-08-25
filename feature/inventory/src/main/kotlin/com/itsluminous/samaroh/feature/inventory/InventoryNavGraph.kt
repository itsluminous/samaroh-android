package com.itsluminous.samaroh.feature.inventory

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.samaroh.core.designsystem.component.PlaceholderScreen
import com.itsluminous.samaroh.core.i18n.R

/** Route of the Inventory tab's start destination. */
const val INVENTORY_ROUTE = "inventory"

/**
 * Inventory feature graph (Wave 0 skeleton — W1-C implements current stock with FIFO
 * valuation, masterlist CRUD and the transaction dialog).
 */
fun NavGraphBuilder.inventoryGraph() {
    composable(INVENTORY_ROUTE) {
        PlaceholderScreen(featureNameRes = R.string.common_nav_inventory, icon = Icons.Filled.Inventory2)
    }
}
