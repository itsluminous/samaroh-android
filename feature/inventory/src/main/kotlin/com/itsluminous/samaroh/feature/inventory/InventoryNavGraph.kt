package com.itsluminous.samaroh.feature.inventory

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import com.itsluminous.samaroh.core.data.navigation.CurrentScreenTracker
import com.itsluminous.samaroh.core.data.navigation.HelpDeepLinkIds
import com.itsluminous.samaroh.core.data.navigation.HelpScreenIds
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
 * One-shot targets are [State]s read at composition time (ADR-099: the shell's root
 * graph is built once).
 *
 * @param openMasterlist switches the toggle to the Masterlist screen (web App Link,
 *   ADR-033); consumed via [onMasterlistDeepLinkConsumed].
 * @param onMasterlistDeepLinkConsumed clears the pending masterlist target once handled.
 * @param sharedPhotoUri share-sheet "Set as item photo" (ADR-086): opens the Masterlist,
 *   asks which item, then the editor with the picture pre-staged; consumed via
 *   [onSharedPhotoConsumed].
 * @param helpTarget a Help "Show me" deep link (ADR-108) this host lands on: the stock
 *   list for `inventory/stock` and `inventory/item` (an item id the guide cannot know),
 *   the masterlist for `inventory/masterlist`. A [State] (ADR-099); consumed via
 *   [onHelpTargetConsumed].
 * @param onHelpTargetConsumed clears the pending Show-me target once landed.
 */
fun NavGraphBuilder.inventoryGraph(
    openMasterlist: State<Boolean> = mutableStateOf(false),
    onMasterlistDeepLinkConsumed: () -> Unit = {},
    sharedPhotoUri: State<Uri?> = mutableStateOf(null),
    onSharedPhotoConsumed: () -> Unit = {},
    helpTarget: State<String?> = mutableStateOf(null),
    onHelpTargetConsumed: () -> Unit = {},
) {
    composable(INVENTORY_ROUTE) {
        val masterlist by openMasterlist
        val photoUri by sharedPhotoUri
        val pendingHelpTarget by helpTarget
        InventoryHost(
            openMasterlist = masterlist || photoUri != null,
            onMasterlistDeepLinkConsumed = onMasterlistDeepLinkConsumed,
            sharedPhotoUri = photoUri,
            onSharedPhotoConsumed = onSharedPhotoConsumed,
            helpTarget = pendingHelpTarget,
            onHelpTargetConsumed = onHelpTargetConsumed,
        )
    }
}

@Composable
private fun InventoryHost(
    openMasterlist: Boolean,
    onMasterlistDeepLinkConsumed: () -> Unit,
    sharedPhotoUri: Uri?,
    onSharedPhotoConsumed: () -> Unit,
    helpTarget: String?,
    onHelpTargetConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    // Help "Show me" (ADR-108): back to the home destination; InventoryRoute picks the view.
    LaunchedEffect(helpTarget) {
        if (helpTarget in HelpDeepLinkIds.inventory) navController.popBackStack(INVENTORY_HOME_ROUTE, inclusive = false)
    }
    NavHost(navController = navController, startDestination = INVENTORY_HOME_ROUTE) {
        composable(INVENTORY_HOME_ROUTE) {
            InventoryRoute(
                onOpenItem = { itemId -> navController.navigate(itemDetailRoute(itemId)) },
                openMasterlist = openMasterlist,
                onMasterlistDeepLinkConsumed = onMasterlistDeepLinkConsumed,
                sharedPhotoUri = sharedPhotoUri,
                onSharedPhotoConsumed = onSharedPhotoConsumed,
                helpTarget = helpTarget,
                onHelpTargetConsumed = onHelpTargetConsumed,
            )
        }
        composable(
            route = ITEM_DETAIL_ROUTE,
            arguments = listOf(navArgument(ITEM_DETAIL_ID_ARG) { type = NavType.StringType }),
        ) {
            LaunchedEffect(Unit) { CurrentScreenTracker.setScreen(HelpScreenIds.INVENTORY_ITEM) }
            ItemDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun InventoryRoute(
    onOpenItem: (String) -> Unit,
    openMasterlist: Boolean,
    onMasterlistDeepLinkConsumed: () -> Unit,
    sharedPhotoUri: Uri? = null,
    onSharedPhotoConsumed: () -> Unit = {},
    helpTarget: String? = null,
    onHelpTargetConsumed: () -> Unit = {},
) {
    var screen by rememberSaveable { mutableStateOf(InventoryScreen.STOCK) }
    // App-Link masterlist target (ADR-033): flip the toggle once, then consume.
    LaunchedEffect(openMasterlist) {
        if (openMasterlist) {
            screen = InventoryScreen.MASTERLIST
            onMasterlistDeepLinkConsumed()
        }
    }
    // Help "Show me" (ADR-108): masterlist for its id, the stock list for the others.
    LaunchedEffect(helpTarget) {
        if (helpTarget !in HelpDeepLinkIds.inventory) return@LaunchedEffect
        screen = if (helpTarget == HelpDeepLinkIds.INVENTORY_MASTERLIST) InventoryScreen.MASTERLIST else InventoryScreen.STOCK
        onHelpTargetConsumed()
    }
    // Help/assistant context (ADR-108): stock and masterlist are distinct guide screens.
    LaunchedEffect(screen) {
        CurrentScreenTracker.setScreen(
            if (screen == InventoryScreen.MASTERLIST) HelpScreenIds.INVENTORY_MASTERLIST else HelpScreenIds.INVENTORY_STOCK,
        )
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
            MasterlistScreen(
                onOpenStock = { screen = InventoryScreen.STOCK },
                sharedPhotoUri = sharedPhotoUri,
                onSharedPhotoConsumed = onSharedPhotoConsumed,
            )
        }
    }
}
