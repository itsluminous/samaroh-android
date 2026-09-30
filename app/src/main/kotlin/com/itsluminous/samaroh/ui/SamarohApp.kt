package com.itsluminous.samaroh.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.itsluminous.samaroh.applink.AppLink
import com.itsluminous.samaroh.core.data.share.ShareAction
import com.itsluminous.samaroh.core.data.share.ShareIntakeHolder
import com.itsluminous.samaroh.core.data.share.ShareRouting
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.OfflineBanner
import com.itsluminous.samaroh.core.designsystem.theme.SamarohMotion
import com.itsluminous.samaroh.core.designsystem.theme.rememberReducedMotion
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.booking.bookingGraph
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.expenses.expensesGraph
import com.itsluminous.samaroh.feature.expenses.sharetarget.ShareTargetHolder
import com.itsluminous.samaroh.feature.expenses.sharetarget.SharedInvoiceFile
import com.itsluminous.samaroh.feature.files.FILES_ROUTE
import com.itsluminous.samaroh.feature.files.filesGraph
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE
import com.itsluminous.samaroh.feature.inventory.inventoryGraph
import com.itsluminous.samaroh.feature.menu.MENU_ROUTE
import com.itsluminous.samaroh.feature.menu.SYNC_STATUS_ROUTE
import com.itsluminous.samaroh.feature.menu.menuGraph
import com.itsluminous.samaroh.feature.menu.syncStatusGraph
import com.itsluminous.samaroh.feature.notes.NOTES_ROUTE
import com.itsluminous.samaroh.feature.notes.notesGraph
import com.itsluminous.samaroh.feature.onboarding.ONBOARDING_ROUTE
import com.itsluminous.samaroh.feature.onboarding.ONBOARDING_SIGN_IN_ROUTE
import com.itsluminous.samaroh.feature.onboarding.onboardingGraph
import com.itsluminous.samaroh.feature.reports.reportsGraph
import com.itsluminous.samaroh.feature.reports.reportsRoute
import com.itsluminous.samaroh.share.ShareBlocked
import com.itsluminous.samaroh.share.ShareChooserDialog
import dagger.hilt.android.EntryPointAccessors

/** The module tabs (§0, ADR-085/087). Labels are catalog keys; icons are decorative duplicates of the label. */
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
        // NOTES tab (ADR-077): hidden without notes.view like every module tab.
        TopLevelDestination(NOTES_ROUTE, R.string.notes_nav_tab, Icons.AutoMirrored.Filled.StickyNote2),
        // FILES tab (ADR-085/087): fifth module — takes the bar slot the Menu used to
        // hold; the Menu itself lives behind the title-bar kebab now.
        TopLevelDestination(FILES_ROUTE, R.string.files_nav_tab, Icons.Filled.Folder),
    )

/**
 * App shell (Wave-1 integration): first launch routes to onboarding (§4.0) until the
 * completion flag is set; afterwards the module-tab scaffold hosts the feature graphs with
 * the §4.5 app bar (cloud sync indicator + Menu kebab, ADR-087) and offline banner.
 *
 * @param pendingBookingId booking id from a reminder-notification launch intent — routes
 *   to the Booking tab and opens that booking's card (§4.1 deep link).
 * @param onBookingDeepLinkConsumed clears the pending id once handed to the feature.
 * @param pendingAppLink destination parsed from a `https://samaroh-web.vercel.app` VIEW
 *   intent (ADR-033) — routes to the matching tab; ledger/masterlist/settings sub-targets
 *   are handed to the feature graphs, which consume them via [onAppLinkConsumed].
 * @param onAppLinkConsumed clears the pending App Link once routed.
 * @param pendingShare files arrived via the Save to Samaroh share target (ADR-086): the
 *   shell shows the chooser and routes the pick; cleared via [onShareConsumed].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamarohApp(
    pendingBookingId: String?,
    onBookingDeepLinkConsumed: () -> Unit,
    pendingAppLink: AppLink? = null,
    onAppLinkConsumed: () -> Unit = {},
    pendingShare: Boolean = false,
    onShareConsumed: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val isOnline by rememberIsOnline()
    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    val visibleTabsState by viewModel.visibleTabs.collectAsStateWithLifecycle()
    val syncIndicator by viewModel.syncIndicator.collectAsStateWithLifecycle()
    val signedOut by viewModel.signedOut.collectAsStateWithLifecycle()
    val activityContext = LocalContext.current

    // Wait for the DataStore read before choosing the start destination (no flicker).
    val onboarded = onboardingComplete ?: return
    // Tab-level §3 gate: wait for the first permission recompute too — rendering all
    // tabs and then dropping one would flash a tab the member cannot view.
    val visibleTabs = visibleTabsState ?: return
    val shareContext by viewModel.shareContext.collectAsStateWithLifecycle()
    val shareIntakeHolder = remember(activityContext) { ShellEntryPoints.of(activityContext).shareIntakeHolder() }
    val invoiceShareHolder = remember(activityContext) { ShellEntryPoints.of(activityContext).invoiceShareHolder() }
    var pendingShareInvoice by remember { mutableStateOf(false) }
    var pendingItemPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingSaveToFiles by remember { mutableStateOf(false) }
    // Saveable so activity recreation (locale/theme change) keeps the SAME nav graph —
    // a changed startDestination breaks NavController state restoration. The completion
    // navigation (not a graph swap) moves the user on; a process restart re-reads the flag.
    // Booking hidden (no booking.view) → the first visible tab; no module at all → Menu.
    val startDestination = rememberSaveable { if (onboarded) NavPermissions.startDestination(visibleTabs) else ONBOARDING_ROUTE }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val inOnboarding =
        currentDestination?.hierarchy?.any { it.route == ONBOARDING_ROUTE }
            ?: (startDestination == ONBOARDING_ROUTE)

    // Tab-level §3 gate, reactive leg: when a sync recompute revokes the module the user
    // is currently ON (or the saved start tab is no longer viewable), move to the first
    // visible tab. Hierarchy matching (ADR-042) covers nested subscreens too.
    val hierarchyRoutes =
        currentDestination
            ?.hierarchy
            ?.mapNotNull { it.route }
            ?.toList()
            .orEmpty()
    val currentTab = NavTabSelection.selectedTab(hierarchyRoutes)
    // Re-read on every back-stack change (backStackEntry above is the State that
    // recomposes us): whether the Menu home should offer a back arrow (ADR-087).
    val menuHasParent = backStackEntry != null && navController.previousBackStackEntry != null
    LaunchedEffect(visibleTabs, currentTab) {
        if (currentTab != null && currentTab !in visibleTabs) {
            navController.navigate(NavPermissions.startDestination(visibleTabs)) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
        }
    }

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

    // Web App Link (ADR-033): switch to the target tab with the bottom-bar navigation
    // pattern; tab-only links are consumed here, sub-targets (ledger/masterlist/settings)
    // by the feature graphs below once they finished navigating.
    // The Menu is not a tab (ADR-087): its links PUSH the Menu graph over the current
    // tab, exactly like the kebab, so back returns to where the user was.
    fun openMenu() {
        navController.navigate(MENU_TAB_ROUTE) { launchSingleTop = true }
    }

    // ADR-089 re-sign-in: the signed-out banner / Sync status / Menu identity row open the
    // onboarding SIGN-IN step on top of the current screen — unlike the ADR-040 sign-out
    // landing, local data is intact and back returns here. A successful sign-in as the
    // same account ends onboarding (returning-user fast path) and drains the held queue.
    fun openSignIn() {
        navController.navigate(ONBOARDING_SIGN_IN_ROUTE) { launchSingleTop = true }
    }
    LaunchedEffect(pendingAppLink, onboarded) {
        val link = pendingAppLink
        if (link != null && onboarded) {
            val tabRoute =
                when (link) {
                    AppLink.Booking -> BOOKING_ROUTE
                    is AppLink.Expenses -> EXPENSES_ROUTE
                    is AppLink.Inventory -> INVENTORY_ROUTE
                    AppLink.Files -> FILES_ROUTE
                    is AppLink.Menu, AppLink.Reports -> null
                }
            if (tabRoute != null) {
                navController.navigate(tabRoute) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            } else {
                openMenu()
            }
            // Reports sits on the root NavHost above the Menu (back returns to Menu).
            if (link == AppLink.Reports) {
                navController.navigate(reportsRoute()) { launchSingleTop = true }
            }
            val featureConsumes =
                when (link) {
                    is AppLink.Expenses -> link.partyId != null
                    is AppLink.Inventory -> link.masterlist
                    is AppLink.Menu -> link.settings
                    else -> false
                }
            if (!featureConsumes) onAppLinkConsumed()
        }
    }

    // Share-chooser routing (ADR-086): each pick lands on its tab; the feature graph
    // consumes the flag once it took over the parked payload.
    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // Create-invoice (ADR-078, unchanged): the expenses graph opens the party picker.
    LaunchedEffect(pendingShareInvoice, onboarded) {
        if (pendingShareInvoice && onboarded) navigateToTab(EXPENSES_ROUTE)
    }
    LaunchedEffect(pendingItemPhoto, onboarded) {
        if (pendingItemPhoto != null && onboarded) navigateToTab(INVENTORY_ROUTE)
    }
    LaunchedEffect(pendingSaveToFiles, onboarded) {
        if (pendingSaveToFiles && onboarded) navigateToTab(FILES_ROUTE)
    }
    // The chooser itself (ADR-086): rows per payload + permissions, permission-hidden.
    if (pendingShare && onboarded) {
        val files = shareIntakeHolder.peek()
        val context = shareContext
        val blocked =
            when {
                files.isEmpty() -> ShareBlocked.UNSUPPORTED
                context == null || !context.hasBusiness -> ShareBlocked.SIGNED_OUT
                else -> null
            }
        val actions =
            if (blocked == null &&
                context != null
            ) {
                ShareRouting.availableActions(files, context.isOwner, context.permissions)
            } else {
                emptyList()
            }
        ShareChooserDialog(
            actions = actions,
            blocked = blocked ?: if (actions.isEmpty()) ShareBlocked.NO_OPTIONS else null,
            onPick = { action ->
                onShareConsumed()
                when (action) {
                    ShareAction.CREATE_INVOICE -> {
                        val single = shareIntakeHolder.consume().single()
                        invoiceShareHolder.set(SharedInvoiceFile(single.uri, single.mimeType, single.displayName))
                        pendingShareInvoice = true
                    }
                    ShareAction.SET_ITEM_PHOTO -> pendingItemPhoto = shareIntakeHolder.consume().single().uri
                    ShareAction.SAVE_TO_FILES -> pendingSaveToFiles = true
                }
            },
            onDismiss = {
                shareIntakeHolder.clear()
                onShareConsumed()
            },
        )
    }

    Scaffold(
        topBar = {
            if (!inOnboarding) {
                // §4.5 app bar: the active business name (fallback: app name pre-onboarding).
                val businessName by viewModel.activeBusinessName.collectAsStateWithLifecycle()
                TopAppBar(
                    title = { AppBarTitle(businessName ?: stringResource(R.string.common_app_name)) },
                    actions = {
                        SyncCloudIcon(
                            indicator = syncIndicator,
                            // Badge > 0 or signed out → open the Sync-status screen (§4.5, ADR-089).
                            onOpenSyncStatus = { navController.navigate(SYNC_STATUS_ROUTE) },
                        )
                        // Menu kebab (ADR-087): right of the sync icon; pushes the Menu
                        // graph over the current tab. Hidden while the Menu is open.
                        if (NavPermissions.showsMenuKebab(hierarchyRoutes)) {
                            ExplainableIcon(
                                icon = Icons.Filled.MoreVert,
                                explanationRes = R.string.common_nav_menu,
                                onClick = ::openMenu,
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            // §3 tab-level gate: only the member's visible module tabs render (hidden,
            // not greyed); the list recomputes live when sync changes permissions. No
            // visible module → no bar (the Menu is behind the kebab, ADR-087).
            if (!inOnboarding && NavPermissions.showsBottomBar(visibleTabs)) {
                NavigationBar {
                    topLevelDestinations.filter { it.route in visibleTabs }.forEach { destination ->
                        val label = stringResource(destination.labelRes)
                        NavigationBarItem(
                            // Hierarchy-based selection (ADR-042): the tab highlights on
                            // every destination nested under it, not just its own route;
                            // inside the Menu graph nothing is selected.
                            selected = currentTab == destination.route,
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
        // consumeWindowInsets is the IME-gap fix: every form screen pins its action row
        // with `imePadding()`, but the shell already spent the bottom-bar + nav-bar
        // insets via `padding` here. Without marking them consumed, each screen's inner
        // Scaffold re-adds the nav-bar inset AND `imePadding()` adds the FULL keyboard
        // height on top of the bottom-bar padding — a dead gap the size of the bottom
        // bar between the save row and the keyboard. Consuming makes descendants pad
        // only the REMAINING inset (keyboard minus what's already padded), so pinned
        // rows sit snugly above the IME on every tab with one shell-level fix.
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
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
            // Signed-out banner (ADR-089): the device lost its session — nothing syncs
            // until the user signs in again. Persistent (not a snackbar) because the
            // condition persists; hidden inside onboarding where sign-in itself is shown.
            if (signedOut && !inOnboarding) {
                SignedOutBanner(onSignIn = ::openSignIn)
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
                expensesGraph(
                    partyIdToOpen = (pendingAppLink as? AppLink.Expenses)?.partyId,
                    onPartyDeepLinkConsumed = onAppLinkConsumed,
                    shareTargetRequested = pendingShareInvoice && onboarded,
                    onShareTargetConsumed = { pendingShareInvoice = false },
                )
                inventoryGraph(
                    openMasterlist = (pendingAppLink as? AppLink.Inventory)?.masterlist == true,
                    onMasterlistDeepLinkConsumed = onAppLinkConsumed,
                    sharedPhotoUri = pendingItemPhoto,
                    onSharedPhotoConsumed = { pendingItemPhoto = null },
                )
                notesGraph()
                // FILES tab (ADR-085); the share chooser's Save to Files lands here (ADR-086).
                filesGraph(
                    saveToFilesRequested = pendingSaveToFiles && onboarded,
                    onSaveToFilesConsumed = { pendingSaveToFiles = false },
                )
                // Menu graph (ADR-042/087): the Menu screens PLUS its root-level
                // subscreens (Reports, Sync status) nest under one graph route; the
                // kebab pushes it over the current tab, back pops it.
                navigation(route = MENU_TAB_ROUTE, startDestination = MENU_ROUTE) {
                    menuGraph(
                        // Back arrow on the Menu home: only when a tab lies beneath it
                        // (a member with no module starts ON the Menu — nothing to pop to).
                        onBack = if (menuHasParent) ({ navController.popBackStack() }) else null,
                        onOpenReports = { navController.navigate(reportsRoute()) },
                        // Menu-search report result (ADR-075): deep link to the detail.
                        onOpenReportDetail = { reportArg -> navController.navigate(reportsRoute(reportArg)) },
                        openSettings = (pendingAppLink as? AppLink.Menu)?.settings == true,
                        onSettingsDeepLinkConsumed = onAppLinkConsumed,
                        // Sign-out (ADR-040): session dropped + local data wiped — land on the
                        // onboarding SIGN-IN step (language already chosen) with the whole
                        // back stack cleared so back cannot return to the signed-in UI.
                        onSignedOut = {
                            navController.navigate(ONBOARDING_SIGN_IN_ROUTE) {
                                popUpTo(0) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        // Identity row "Sign in" while signed out (ADR-089).
                        onSignIn = ::openSignIn,
                    )
                    reportsGraph()
                    syncStatusGraph(onBack = { navController.popBackStack() }, onSignIn = ::openSignIn)
                }
                onboardingGraph(
                    onOnboardingComplete = {
                        viewModel.completeOnboarding()
                        // First visible tab (§3): Booking unless the member lacks booking.view;
                        // no module at all → the Menu (ADR-087).
                        navController.navigate(NavPermissions.startDestination(visibleTabs)) {
                            popUpTo(ONBOARDING_ROUTE) { inclusive = true }
                        }
                    },
                    onConnectGoogle = { viewModel.connectGoogle(activityContext) },
                )
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
    // Active-run feedback (§4.5): while a run executes the icon swaps to the plain
    // circular-arrows Sync glyph and spins (rotating a cloud looks odd); with
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
                    icon = Icons.Filled.Sync,
                    explanationRes = R.string.sync_notification_syncing,
                    onClick = onTap,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            // ADR-089: no user session — queued changes are waiting for sign-in and pulls
            // return nothing. Never the green check. Tap opens Sync status (sign-in banner).
            indicator.needsSignIn ->
                ExplainableIcon(
                    icon = Icons.Filled.CloudOff,
                    explanationRes = R.string.sync_signed_out_icon,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onOpenSyncStatus,
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

/**
 * Persistent shell banner while the device has lost its session (ADR-089): explains why
 * nothing syncs and offers the way out. Sits under the offline banner, above the NavHost.
 */
@Composable
private fun SignedOutBanner(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.sync_signed_out_banner),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSignIn) {
                Text(stringResource(R.string.sync_signed_out_action_sign_in))
            }
        }
    }
}

/** The app-bar title never shrinks below this fraction of its base font size. */
internal const val TITLE_MIN_FONT_SCALE = 0.65f

/**
 * Scale selection for the app-bar title: shrink between [TITLE_MIN_FONT_SCALE] and 1×
 * of [fontSize] to keep long business names on one line (same BasicText autoSize
 * pattern as the report money cells); anything still wider ellipsizes. Returns null
 * when [fontSize] is not an sp value (em/unspecified) — no scaling then, plain layout.
 */
internal fun titleAutoSize(fontSize: TextUnit): TextAutoSize? =
    if (fontSize.isSp) {
        TextAutoSize.StepBased(
            minFontSize = fontSize * TITLE_MIN_FONT_SCALE,
            maxFontSize = fontSize,
        )
    } else {
        null
    }

/**
 * App-bar business name (§4.5). Long names shrink their font to stay on ONE line
 * instead of wrapping the app bar taller, down to [TITLE_MIN_FONT_SCALE]; below
 * that they ellipsize.
 */
@Composable
private fun AppBarTitle(text: String) {
    // TopAppBar provides titleLarge + content color via the locals; BasicText applies
    // no content color of its own, so resolve it the way material Text does.
    val style = LocalTextStyle.current
    val autoSize = titleAutoSize(style.fontSize)
    if (autoSize == null) {
        Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    BasicText(
        text = text,
        style = style.merge(TextStyle(color = LocalContentColor.current)),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = autoSize,
    )
}

/**
 * Hilt entry point for the two share holders the shell hands payloads to (ADR-086):
 * the unified intake (any files) and the expenses Create-invoice holder (ADR-078).
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ShellEntryPoints {
    fun shareIntakeHolder(): ShareIntakeHolder

    fun invoiceShareHolder(): ShareTargetHolder

    companion object {
        fun of(context: android.content.Context): ShellEntryPoints =
            EntryPointAccessors.fromApplication(context.applicationContext, ShellEntryPoints::class.java)
    }
}
