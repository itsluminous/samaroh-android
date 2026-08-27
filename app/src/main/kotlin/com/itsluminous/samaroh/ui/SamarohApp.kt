package com.itsluminous.samaroh.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.OfflineBanner
import com.itsluminous.samaroh.core.designsystem.theme.SamarohMotion
import com.itsluminous.samaroh.core.designsystem.theme.rememberReducedMotion
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.booking.bookingGraph
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.expenses.expensesGraph
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE
import com.itsluminous.samaroh.feature.inventory.inventoryGraph
import com.itsluminous.samaroh.feature.menu.MENU_ROUTE
import com.itsluminous.samaroh.feature.menu.SYNC_STATUS_ROUTE
import com.itsluminous.samaroh.feature.menu.menuGraph
import com.itsluminous.samaroh.feature.menu.syncStatusGraph
import com.itsluminous.samaroh.feature.onboarding.ONBOARDING_ROUTE
import com.itsluminous.samaroh.feature.onboarding.onboardingGraph
import com.itsluminous.samaroh.feature.reports.REPORTS_ROUTE
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

/**
 * App shell (Wave-1 integration): first launch routes to onboarding (§4.0) until the
 * completion flag is set; afterwards the four-tab scaffold hosts the feature graphs with
 * the §4.5 app bar (cloud sync indicator) and offline banner.
 *
 * @param pendingBookingId booking id from a reminder-notification launch intent — routes
 *   to the Booking tab and opens that booking's card (§4.1 deep link).
 * @param onBookingDeepLinkConsumed clears the pending id once handed to the feature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamarohApp(
    pendingBookingId: String?,
    onBookingDeepLinkConsumed: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val isOnline by rememberIsOnline()
    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    val syncIndicator by viewModel.syncIndicator.collectAsStateWithLifecycle()
    val activityContext = LocalContext.current

    // Wait for the DataStore read before choosing the start destination (no flicker).
    val onboarded = onboardingComplete ?: return
    // Saveable so activity recreation (locale/theme change) keeps the SAME nav graph —
    // a changed startDestination breaks NavController state restoration. The completion
    // navigation (not a graph swap) moves the user on; a process restart re-reads the flag.
    val startDestination = rememberSaveable { if (onboarded) BOOKING_ROUTE else ONBOARDING_ROUTE }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val inOnboarding =
        currentDestination?.hierarchy?.any { it.route == ONBOARDING_ROUTE }
            ?: (startDestination == ONBOARDING_ROUTE)

    // Reminder-notification deep link: land on the Booking tab (§4.1); the booking graph
    // opens the specific card via [pendingBookingId] below.
    LaunchedEffect(pendingBookingId, onboarded) {
        if (pendingBookingId != null && onboarded) {
            navController.navigate(BOOKING_ROUTE) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        topBar = {
            if (!inOnboarding) {
                // §4.5 app bar: the active business name (fallback: app name pre-onboarding).
                val businessName by viewModel.activeBusinessName.collectAsStateWithLifecycle()
                TopAppBar(
                    title = { Text(businessName ?: stringResource(R.string.common_app_name)) },
                    actions = {
                        SyncCloudIcon(
                            indicator = syncIndicator,
                            // Badge > 0 → open the Sync-status pending list (§4.5).
                            onOpenSyncStatus = { navController.navigate(SYNC_STATUS_ROUTE) },
                        )
                    },
                )
            }
        },
        bottomBar = {
            if (!inOnboarding) {
                NavigationBar {
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
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Offline-banner slot (§4.5): persistent thin banner while disconnected.
            val bannerReducedMotion = rememberReducedMotion()
            AnimatedVisibility(
                visible = !isOnline,
                enter =
                    if (bannerReducedMotion) {
                        androidx.compose.animation.EnterTransition.None
                    } else {
                        androidx.compose.animation.expandVertically(SamarohMotion.enterSpec()) +
                            androidx.compose.animation.fadeIn(SamarohMotion.enterSpec())
                    },
                exit =
                    if (bannerReducedMotion) {
                        androidx.compose.animation.ExitTransition.None
                    } else {
                        androidx.compose.animation.shrinkVertically(SamarohMotion.exitSpec()) +
                            androidx.compose.animation.fadeOut(SamarohMotion.exitSpec())
                    },
            ) {
                OfflineBanner()
            }
            // Nav-level motion (§6 polish): consistent fade-through from the shared spec,
            // disabled entirely when the user has reduced motion on.
            val reducedMotion = rememberReducedMotion()
            NavHost(
                navController = navController,
                startDestination = startDestination,
                enterTransition = { SamarohMotion.screenEnter(reducedMotion) },
                exitTransition = { SamarohMotion.screenExit(reducedMotion) },
                popEnterTransition = { SamarohMotion.screenEnter(reducedMotion) },
                popExitTransition = { SamarohMotion.screenExit(reducedMotion) },
            ) {
                bookingGraph(
                    bookingIdToOpen = pendingBookingId,
                    onBookingOpened = onBookingDeepLinkConsumed,
                )
                expensesGraph()
                inventoryGraph()
                menuGraph(onOpenReports = { navController.navigate(REPORTS_ROUTE) })
                onboardingGraph(
                    onOnboardingComplete = {
                        viewModel.completeOnboarding()
                        navController.navigate(BOOKING_ROUTE) {
                            popUpTo(ONBOARDING_ROUTE) { inclusive = true }
                        }
                    },
                    onConnectGoogle = { viewModel.connectGoogle(activityContext) },
                )
                reportsGraph()
                syncStatusGraph(onBack = { navController.popBackStack() })
            }
        }
    }
}

/** §4.5 cloud status icon: ✅ synced / 🔄 pending / ☁️⚠️ + count when items error out. */
@Composable
private fun SyncCloudIcon(
    indicator: SyncIndicator,
    onOpenSyncStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val badgeCount = if (indicator.errorCount > 0) indicator.errorCount else indicator.pendingCount
    // Tapping the icon while items are pending/errored opens the Sync-status list (§4.5).
    val onTap: (() -> Unit)? = if (badgeCount > 0) onOpenSyncStatus else null
    // Active-run feedback (§4.5): the CloudSync icon spins while a run executes; with
    // reduced motion on, a static badge dot marks the run instead of the rotation.
    val reducedMotion = rememberReducedMotion()
    val spinning = indicator.syncing && !reducedMotion
    val rotation: Float =
        if (spinning) {
            val transition = rememberInfiniteTransition(label = "sync_spin")
            transition
                .animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1200, easing = LinearEasing)),
                    label = "sync_spin_angle",
                ).value
        } else {
            0f
        }
    BadgedBox(
        badge = {
            if (badgeCount > 0) {
                Badge { Text(badgeCount.toString()) }
            } else if (indicator.syncing && reducedMotion) {
                // Reduced-motion fallback: a plain dot instead of the spin.
                Badge()
            }
        },
        modifier = modifier,
    ) {
        when {
            indicator.syncing ->
                ExplainableIcon(
                    icon = Icons.Filled.CloudSync,
                    explanationRes = R.string.sync_notification_syncing,
                    onClick = onTap,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            indicator.errorCount > 0 ->
                ExplainableIcon(
                    icon = Icons.Filled.CloudOff,
                    explanationRes = R.string.settings_sync_errors_title,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onTap,
                )
            indicator.pendingCount > 0 ->
                ExplainableIcon(
                    icon = Icons.Filled.CloudSync,
                    explanationRes = R.string.common_state_pending,
                    onClick = onTap,
                )
            else ->
                ExplainableIcon(
                    icon = Icons.Filled.CloudDone,
                    explanationRes = R.string.common_state_synced,
                )
        }
    }
}
