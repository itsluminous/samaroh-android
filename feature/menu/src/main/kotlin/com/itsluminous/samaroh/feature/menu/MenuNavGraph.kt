package com.itsluminous.samaroh.feature.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.itsluminous.samaroh.feature.menu.ui.about.AboutScreen
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
fun NavGraphBuilder.syncStatusGraph(onBack: () -> Unit) {
    composable(SYNC_STATUS_ROUTE) {
        SyncStatusScreen(onBack = onBack)
    }
}

/**
 * Menu feature graph (§4.4): Settings (language/theme/reminders/Google/backup/sync/
 * business profile), Members (owner only), About. Sub-navigation is self-contained in a
 * nested NavHost so the app shell keeps calling `menuGraph()` unchanged; [onOpenReports]
 * is wired by the app once `feature:reports` lands (W2-A).
 *
 * @param openSettings lands on the Settings screen (web App-Link `/menu/settings…`,
 *   ADR-033); consumed via [onSettingsDeepLinkConsumed].
 * @param onSettingsDeepLinkConsumed clears the pending settings target once handled.
 * @param onSignedOut sign-out completed on the Menu home identity row (ADR-040) — the
 *   app shell routes to the onboarding sign-in step with a cleared back stack.
 * @param onOpenReportDetail a Menu-search result picked a specific report (ADR-075):
 *   the argument is `feature:reports`' `ReportType.routeArg` wire value, which the app
 *   shell turns into a reports deep link (feature modules never depend on each other).
 */
fun NavGraphBuilder.menuGraph(
    onOpenReports: () -> Unit = {},
    openSettings: Boolean = false,
    onSettingsDeepLinkConsumed: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    onOpenReportDetail: (String) -> Unit = {},
) {
    composable(MENU_ROUTE) {
        MenuTabHost(
            onOpenReports = onOpenReports,
            openSettings = openSettings,
            onSettingsDeepLinkConsumed = onSettingsDeepLinkConsumed,
            onSignedOut = onSignedOut,
            onOpenReportDetail = onOpenReportDetail,
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
    }

@Composable
private fun MenuTabHost(
    onOpenReports: () -> Unit,
    openSettings: Boolean,
    onSettingsDeepLinkConsumed: () -> Unit,
    onSignedOut: () -> Unit,
    onOpenReportDetail: (String) -> Unit,
) {
    val navController = rememberNavController()
    // App-Link settings target (ADR-033): push Settings over Home once, then consume.
    LaunchedEffect(openSettings) {
        if (openSettings) {
            navController.navigate(MenuRoutes.SETTINGS) { launchSingleTop = true }
            onSettingsDeepLinkConsumed()
        }
    }
    NavHost(navController = navController, startDestination = MenuRoutes.HOME) {
        composable(MenuRoutes.HOME) {
            MenuHomeScreen(
                onOpenMenuScreen = { target ->
                    navController.navigate(routeFor(target)) { launchSingleTop = true }
                },
                onOpenReport = { reportArg ->
                    if (reportArg == null) onOpenReports() else onOpenReportDetail(reportArg)
                },
                onSignedOut = onSignedOut,
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
            SyncStatusScreen(onBack = { navController.popBackStack() })
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
    }
}
