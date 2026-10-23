package com.itsluminous.samaroh.feature.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.itsluminous.samaroh.core.data.navigation.CurrentScreenTracker
import com.itsluminous.samaroh.core.data.navigation.HelpDeepLinkIds
import com.itsluminous.samaroh.core.data.navigation.HelpScreenIds
import com.itsluminous.samaroh.feature.menu.ui.about.AboutScreen
import com.itsluminous.samaroh.feature.menu.ui.help.HelpScreen
import com.itsluminous.samaroh.feature.menu.ui.help.HelpViewModel
import com.itsluminous.samaroh.feature.menu.ui.home.MenuHomeScreen
import com.itsluminous.samaroh.feature.menu.ui.members.MembersScreen
import com.itsluminous.samaroh.feature.menu.ui.search.MenuScreenTarget
import com.itsluminous.samaroh.feature.menu.ui.settings.BusinessProfileScreen
import com.itsluminous.samaroh.feature.menu.ui.settings.EventTypesScreen
import com.itsluminous.samaroh.feature.menu.ui.settings.LanguagePickerScreen
import com.itsluminous.samaroh.feature.menu.ui.settings.ReminderSettingsScreen
import com.itsluminous.samaroh.feature.menu.ui.settings.SettingsScreen
import com.itsluminous.samaroh.feature.menu.ui.settings.SyncStatusScreen

/** Route of the Menu tab's start destination. */
const val MENU_ROUTE = "menu"

/**
 * Top-level route for the Sync-status screen (§4.4/§4.5): registered by the app shell so
 * the app-bar cloud icon can open the pending list directly; the Menu tab's nested
 * Settings graph reuses the same [SyncStatusScreen].
 */
const val SYNC_STATUS_ROUTE = "sync_status"

/** Registers the top-level Sync-status destination on the app's root NavHost. */
fun NavGraphBuilder.syncStatusGraph(
    onBack: () -> Unit,
    onSignIn: () -> Unit = {},
) {
    composable(SYNC_STATUS_ROUTE) {
        // Help/assistant context (ADR-108): the guide files Sync status under Settings.
        LaunchedEffect(Unit) { CurrentScreenTracker.setScreen(HelpScreenIds.SETTINGS) }
        SyncStatusScreen(onBack = onBack, onSignIn = onSignIn)
    }
}

/**
 * Menu feature graph (§4.4): Settings (language/theme/reminders/Google/backup/sync/
 * business profile), Members (owner only), About. Sub-navigation is self-contained in a
 * nested NavHost so the app shell keeps calling `menuGraph()` unchanged; [onOpenReports]
 * is wired by the app once `feature:reports` lands (W2-A).
 *
 * @param openSettings lands on the Settings screen (web App-Link `/menu/settings…`,
 *   ADR-033); consumed via [onSettingsDeepLinkConsumed]. A [State] (ADR-099: the shell's
 *   root graph is built once, so one-shot inputs are read at composition time).
 * @param onSettingsDeepLinkConsumed clears the pending settings target once handled.
 * @param onSignedOut sign-out completed on the Menu home identity row (ADR-040) — the
 *   app shell routes to the onboarding sign-in step with a cleared back stack.
 * @param onOpenReportDetail a Menu-search result picked a specific report (ADR-075):
 *   the argument is `feature:reports`' `ReportType.routeArg` wire value, which the app
 *   shell turns into a reports deep link (feature modules never depend on each other).
 * @param canGoBack the Menu is pushed over a tab by the title-bar kebab (ADR-087): the Menu
 *   home shows a back arrow that pops it via [onBack]; false when the Menu is the start
 *   destination (a member with no viewable module) — nothing lies beneath it then. A
 *   [State] because it follows the root back stack (ADR-099).
 * @param onBack pops the Menu back to the tab beneath it.
 * @param onSignIn the identity row's "Sign in" while the device has no session (ADR-089)
 *   — the app shell opens the sign-in step on top of the current screen, data intact.
 * @param openHelp the contextual `?` (ADR-108): open the Help screen scrolled to this guide
 *   screen id (blank = top). Consumed via [onHelpConsumed]. A [State] (ADR-099).
 * @param onHelpConsumed clears the pending Help request once the screen is pushed.
 * @param helpTarget a Help "Show me" deep link this graph owns (the `menu/…` ids, ADR-108): the
 *   host lands on the matching nested screen over the Menu home. Consumed via
 *   [onHelpTargetConsumed]; ids outside [HelpDeepLinkIds.menu] are ignored here.
 * @param onHelpTargetConsumed clears the pending Show-me target once landed.
 */
fun NavGraphBuilder.menuGraph(
    canGoBack: State<Boolean> = mutableStateOf(false),
    onBack: () -> Unit = {},
    onOpenReports: () -> Unit = {},
    openSettings: State<Boolean> = mutableStateOf(false),
    onSettingsDeepLinkConsumed: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    onOpenReportDetail: (String) -> Unit = {},
    onSignIn: () -> Unit = {},
    openHelp: State<String?> = mutableStateOf(null),
    onHelpConsumed: () -> Unit = {},
    helpTarget: State<String?> = mutableStateOf(null),
    onHelpTargetConsumed: () -> Unit = {},
) {
    composable(MENU_ROUTE) {
        val hasParent by canGoBack
        val settingsRequested by openSettings
        val helpRequested by openHelp
        val pendingHelpTarget by helpTarget
        MenuTabHost(
            onBack = if (hasParent) onBack else null,
            onOpenReports = onOpenReports,
            openSettings = settingsRequested,
            onSettingsDeepLinkConsumed = onSettingsDeepLinkConsumed,
            onSignedOut = onSignedOut,
            onOpenReportDetail = onOpenReportDetail,
            onSignIn = onSignIn,
            openHelp = helpRequested,
            onHelpConsumed = onHelpConsumed,
            helpTarget = pendingHelpTarget,
            onHelpTargetConsumed = onHelpTargetConsumed,
        )
    }
}

private object MenuRoutes {
    const val HOME = "menu_home"
    const val SETTINGS = "menu_settings"
    const val LANGUAGE = "menu_settings_language"
    const val REMINDERS = "menu_settings_reminders"
    const val SYNC_STATUS = "menu_settings_sync"
    const val BUSINESS_PROFILE = "menu_settings_business"
    const val EVENT_TYPES = "menu_settings_event_types"
    const val MEMBERS = "menu_members"
    const val ABOUT = "menu_about"
    const val HELP = "menu_help?${HelpViewModel.SCREEN_ARG}={${HelpViewModel.SCREEN_ARG}}"

    /** The Help screen, opened on [screenId]'s section (ADR-108); null/blank = the top. */
    fun help(screenId: String?): String = "menu_help?${HelpViewModel.SCREEN_ARG}=${screenId.orEmpty()}"
}

/**
 * Nested route of a Help "Show me" deep-link id this graph owns (ADR-108, `routes.json`
 * android column). Null for ids other features own.
 */
internal fun routeForHelpTarget(deepLinkId: String): String? =
    when (deepLinkId) {
        HelpDeepLinkIds.MENU_HOME -> MenuRoutes.HOME
        HelpDeepLinkIds.MENU_SETTINGS -> MenuRoutes.SETTINGS
        HelpDeepLinkIds.MENU_SETTINGS_LANGUAGE -> MenuRoutes.LANGUAGE
        HelpDeepLinkIds.MENU_SETTINGS_SYNC -> MenuRoutes.SYNC_STATUS
        HelpDeepLinkIds.MENU_SETTINGS_BUSINESS -> MenuRoutes.BUSINESS_PROFILE
        HelpDeepLinkIds.MENU_SETTINGS_EVENT_TYPES -> MenuRoutes.EVENT_TYPES
        HelpDeepLinkIds.MENU_SETTINGS_REMINDERS -> MenuRoutes.REMINDERS
        HelpDeepLinkIds.MENU_MEMBERS -> MenuRoutes.MEMBERS
        HelpDeepLinkIds.MENU_ABOUT -> MenuRoutes.ABOUT
        else -> null
    }

/**
 * Nested route of a Menu-search screen target (ADR-075). Search results deep-link into
 * the nested graph with this mapping; kept exhaustive so a new target cannot compile
 * without a route (and tested for distinctness).
 */
internal fun routeFor(target: MenuScreenTarget): String =
    when (target) {
        MenuScreenTarget.SETTINGS -> MenuRoutes.SETTINGS
        MenuScreenTarget.LANGUAGE -> MenuRoutes.LANGUAGE
        MenuScreenTarget.REMINDERS -> MenuRoutes.REMINDERS
        MenuScreenTarget.SYNC_STATUS -> MenuRoutes.SYNC_STATUS
        MenuScreenTarget.BUSINESS_PROFILE -> MenuRoutes.BUSINESS_PROFILE
        MenuScreenTarget.EVENT_TYPES -> MenuRoutes.EVENT_TYPES
        MenuScreenTarget.MEMBERS -> MenuRoutes.MEMBERS
        MenuScreenTarget.ABOUT -> MenuRoutes.ABOUT
        MenuScreenTarget.HELP -> MenuRoutes.help(null)
    }

/**
 * Guide screen id of a Menu-graph route (ADR-108 mapping table): every Settings subscreen
 * (incl. Sync status) is the guide's `settings` page, About has no page of its own and
 * falls under `menu`.
 */
internal fun helpScreenIdFor(route: String?): String? =
    when (route) {
        MenuRoutes.HOME, MenuRoutes.ABOUT -> HelpScreenIds.MENU
        MenuRoutes.SETTINGS, MenuRoutes.LANGUAGE, MenuRoutes.REMINDERS, MenuRoutes.SYNC_STATUS,
        MenuRoutes.BUSINESS_PROFILE, MenuRoutes.EVENT_TYPES,
        -> HelpScreenIds.SETTINGS
        MenuRoutes.MEMBERS -> HelpScreenIds.MEMBERS
        // The Help screen itself: reported so the shell hides the `?` while it is open.
        MenuRoutes.HELP -> HelpScreenIds.HELP
        else -> null
    }

@Composable
private fun MenuTabHost(
    onBack: (() -> Unit)?,
    onOpenReports: () -> Unit,
    openSettings: Boolean,
    onSettingsDeepLinkConsumed: () -> Unit,
    onSignedOut: () -> Unit,
    onOpenReportDetail: (String) -> Unit,
    onSignIn: () -> Unit,
    openHelp: String?,
    onHelpConsumed: () -> Unit,
    helpTarget: String?,
    onHelpTargetConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    // App-Link settings target (ADR-033): push Settings over Home once, then consume.
    LaunchedEffect(openSettings) {
        if (openSettings) {
            navController.navigate(MenuRoutes.SETTINGS) { launchSingleTop = true }
            onSettingsDeepLinkConsumed()
        }
    }
    // Contextual help (ADR-108): push Help (scrolled to the requested screen) over Home.
    LaunchedEffect(openHelp) {
        if (openHelp != null) {
            navController.navigate(MenuRoutes.help(openHelp)) { launchSingleTop = true }
            onHelpConsumed()
        }
    }
    // Help "Show me" for a Menu screen (ADR-108): land on it over Home — so back returns
    // to the Menu home, not to the Help screen the request came from — then consume.
    LaunchedEffect(helpTarget) {
        val route = helpTarget?.let(::routeForHelpTarget) ?: return@LaunchedEffect
        navController.popBackStack(MenuRoutes.HOME, inclusive = false)
        if (route != MenuRoutes.HOME) navController.navigate(route) { launchSingleTop = true }
        onHelpTargetConsumed()
    }
    // Help/assistant context (ADR-108): report the guide screen of the inner destination.
    val currentRoute =
        navController
            .currentBackStackEntryAsState()
            .value
            ?.destination
            ?.route
    LaunchedEffect(currentRoute) {
        helpScreenIdFor(currentRoute)?.let(CurrentScreenTracker::setScreen)
    }
    NavHost(navController = navController, startDestination = MenuRoutes.HOME) {
        composable(MenuRoutes.HOME) {
            MenuHomeScreen(
                onBack = onBack,
                onOpenMenuScreen = { target ->
                    navController.navigate(routeFor(target)) { launchSingleTop = true }
                },
                onOpenReport = { reportArg ->
                    if (reportArg == null) onOpenReports() else onOpenReportDetail(reportArg)
                },
                onSignedOut = onSignedOut,
                onSignIn = onSignIn,
            )
        }
        composable(MenuRoutes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenLanguagePicker = { navController.navigate(MenuRoutes.LANGUAGE) },
                onOpenReminderSettings = { navController.navigate(MenuRoutes.REMINDERS) },
                onOpenSyncStatus = { navController.navigate(MenuRoutes.SYNC_STATUS) },
                onOpenBusinessProfile = { navController.navigate(MenuRoutes.BUSINESS_PROFILE) },
                onOpenEventTypes = { navController.navigate(MenuRoutes.EVENT_TYPES) },
            )
        }
        composable(MenuRoutes.LANGUAGE) {
            LanguagePickerScreen(onBack = { navController.popBackStack() })
        }
        composable(MenuRoutes.REMINDERS) {
            ReminderSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(MenuRoutes.SYNC_STATUS) {
            SyncStatusScreen(onBack = { navController.popBackStack() }, onSignIn = onSignIn)
        }
        composable(MenuRoutes.BUSINESS_PROFILE) {
            BusinessProfileScreen(onBack = { navController.popBackStack() })
        }
        composable(MenuRoutes.EVENT_TYPES) {
            EventTypesScreen(onBack = { navController.popBackStack() })
        }
        composable(MenuRoutes.MEMBERS) {
            MembersScreen(onBack = { navController.popBackStack() })
        }
        composable(MenuRoutes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = MenuRoutes.HELP,
            arguments =
                listOf(
                    navArgument(HelpViewModel.SCREEN_ARG) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
        ) {
            HelpScreen(onBack = { navController.popBackStack() })
        }
    }
}
