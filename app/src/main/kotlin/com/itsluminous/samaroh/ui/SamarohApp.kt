package com.itsluminous.samaroh.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.itsluminous.samaroh.core.designsystem.component.OfflineBanner
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.booking.bookingGraph
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.expenses.expensesGraph
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE
import com.itsluminous.samaroh.feature.inventory.inventoryGraph
import com.itsluminous.samaroh.feature.menu.MENU_ROUTE
import com.itsluminous.samaroh.feature.menu.menuGraph
import com.itsluminous.samaroh.feature.onboarding.onboardingGraph
import com.itsluminous.samaroh.feature.reports.reportsGraph

/** The four bottom tabs (§0). Labels are catalog keys; icons are decorative duplicates of the label. */
private data class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val topLevelDestinations =
    listOf(
        TopLevelDestination(BOOKING_ROUTE, R.string.common_nav_booking, Icons.Filled.CalendarMonth),
        TopLevelDestination(EXPENSES_ROUTE, R.string.common_nav_expenses, Icons.Filled.AccountBalanceWallet),
        TopLevelDestination(INVENTORY_ROUTE, R.string.common_nav_inventory, Icons.Filled.Inventory2),
        TopLevelDestination(MENU_ROUTE, R.string.common_nav_menu, Icons.Filled.Menu),
    )

@Composable
fun SamarohApp() {
    val navController = rememberNavController()
    val isOnline by rememberIsOnline()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                topLevelDestinations.forEach { destination ->
                    val label = stringResource(destination.labelRes)
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Offline-banner slot (§4.5): persistent thin banner while disconnected.
            AnimatedVisibility(visible = !isOnline) {
                OfflineBanner()
            }
            NavHost(
                navController = navController,
                startDestination = BOOKING_ROUTE,
            ) {
                bookingGraph()
                expensesGraph()
                inventoryGraph()
                menuGraph()
                onboardingGraph()
                reportsGraph()
            }
        }
    }
}
