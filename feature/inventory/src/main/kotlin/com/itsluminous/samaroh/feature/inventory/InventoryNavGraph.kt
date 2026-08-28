package com.itsluminous.samaroh.feature.inventory

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.itsluminous.samaroh.feature.inventory.ui.CurrentInventoryScreen
import com.itsluminous.samaroh.feature.inventory.ui.ItemDetailScreen
import com.itsluminous.samaroh.feature.inventory.ui.MasterlistScreen

/** Route of the Inventory tab's start destination. */
const val INVENTORY_ROUTE = "inventory"

/** Inner start destination hosting the stock/masterlist toggle. */
private const val INVENTORY_HOME_ROUTE = "inventory/home"

/** Inner per-item detail destination; takes the master-item id. */
private const val ITEM_DETAIL_ROUTE = "inventory/item/{$ITEM_DETAIL_ID_ARG}"

private fun itemDetailRoute(itemId: String) = "inventory/item/$itemId"

/** The two Inventory screens, toggled via the contextual top-bar icon (§4.3). */
private enum class InventoryScreen {
    STOCK,
    MASTERLIST,
}

/**
 * Inventory feature graph (§4.3): the Current Inventory (stock) screen and the
 * Masterlist screen live under one destination and swap via the contextual top-bar
 * toggle — mirroring the reference navigation pattern without leaving the tab.
 * Tapping a stock row pushes the per-item detail destination (transaction history).
 *
 * @param openMasterlist switches the toggle to the Masterlist screen (web App Link,
 *   ADR-033); consumed via [onMasterlistDeepLinkConsumed].
 * @param onMasterlistDeepLinkConsumed clears the pending masterlist target once handled.
 */
fun NavGraphBuilder.inventoryGraph(
    openMasterlist: Boolean = false,
    onMasterlistDeepLinkConsumed: () -> Unit = {},
) {
    composable(INVENTORY_ROUTE) {
        InventoryHost(
            openMasterlist = openMasterlist,
            onMasterlistDeepLinkConsumed = onMasterlistDeepLinkConsumed,
        )
    }
}

@Composable
private fun InventoryHost(
    openMasterlist: Boolean,
    onMasterlistDeepLinkConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = INVENTORY_HOME_ROUTE) {
        composable(INVENTORY_HOME_ROUTE) {
            InventoryRoute(
                onOpenItem = { itemId -> navController.navigate(itemDetailRoute(itemId)) },
                openMasterlist = openMasterlist,
                onMasterlistDeepLinkConsumed = onMasterlistDeepLinkConsumed,
            )
        }
        composable(
            route = ITEM_DETAIL_ROUTE,
            arguments = listOf(navArgument(ITEM_DETAIL_ID_ARG) { type = NavType.StringType }),
        ) {
            ItemDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun InventoryRoute(
    onOpenItem: (String) -> Unit,
    openMasterlist: Boolean,
    onMasterlistDeepLinkConsumed: () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(InventoryScreen.STOCK) }
    // App-Link masterlist target (ADR-033): flip the toggle once, then consume.
    LaunchedEffect(openMasterlist) {
        if (openMasterlist) {
            screen = InventoryScreen.MASTERLIST
            onMasterlistDeepLinkConsumed()
        }
    }
    when (screen) {
        InventoryScreen.STOCK ->
            CurrentInventoryScreen(
                onOpenMasterlist = { screen = InventoryScreen.MASTERLIST },
                onOpenItem = onOpenItem,
            )
        InventoryScreen.MASTERLIST -> {
            // System back returns to the stock screen instead of leaving the tab.
            BackHandler { screen = InventoryScreen.STOCK }
            MasterlistScreen(onOpenStock = { screen = InventoryScreen.STOCK })
        }
    }
}
