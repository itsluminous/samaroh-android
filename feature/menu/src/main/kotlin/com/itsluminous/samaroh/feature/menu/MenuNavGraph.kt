package com.itsluminous.samaroh.feature.menu

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.itsluminous.samaroh.feature.menu.ui.about.AboutScreen
import com.itsluminous.samaroh.feature.menu.ui.home.MenuHomeScreen
import com.itsluminous.samaroh.feature.menu.ui.members.MembersScreen
import com.itsluminous.samaroh.feature.menu.ui.settings.BusinessProfileScreen
import com.itsluminous.samaroh.feature.menu.ui.settings.LanguagePickerScreen
import com.itsluminous.samaroh.feature.menu.ui.settings.ReminderSettingsScreen
import com.itsluminous.samaroh.feature.menu.ui.settings.SettingsScreen
import com.itsluminous.samaroh.feature.menu.ui.settings.SyncStatusScreen

/** Route of the Menu tab's start destination. */
const val MENU_ROUTE = "menu"

/**
 * Menu feature graph (§4.4): Settings (language/theme/reminders/Google/backup/sync/
 * business profile), Members (owner only), About. Sub-navigation is self-contained in a
 * nested NavHost so the app shell keeps calling `menuGraph()` unchanged; [onOpenReports]
 * is wired by the app once `feature:reports` lands (W2-A).
 */
fun NavGraphBuilder.menuGraph(onOpenReports: () -> Unit = {}) {
    composable(MENU_ROUTE) {
        MenuTabHost(onOpenReports = onOpenReports)
    }
}

private object MenuRoutes {
    const val HOME = "menu_home"
    const val SETTINGS = "menu_settings"
    const val LANGUAGE = "menu_settings_language"
    const val REMINDERS = "menu_settings_reminders"
    const val SYNC_STATUS = "menu_settings_sync"
    const val BUSINESS_PROFILE = "menu_settings_business"
    const val MEMBERS = "menu_members"
    const val ABOUT = "menu_about"
}

@Composable
private fun MenuTabHost(onOpenReports: () -> Unit) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = MenuRoutes.HOME) {
        composable(MenuRoutes.HOME) {
            MenuHomeScreen(
                onOpenSettings = { navController.navigate(MenuRoutes.SETTINGS) },
                onOpenReports = onOpenReports,
                onOpenMembers = { navController.navigate(MenuRoutes.MEMBERS) },
                onOpenAbout = { navController.navigate(MenuRoutes.ABOUT) },
            )
        }
        composable(MenuRoutes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenLanguagePicker = { navController.navigate(MenuRoutes.LANGUAGE) },
                onOpenReminderSettings = { navController.navigate(MenuRoutes.REMINDERS) },
                onOpenSyncStatus = { navController.navigate(MenuRoutes.SYNC_STATUS) },
                onOpenBusinessProfile = { navController.navigate(MenuRoutes.BUSINESS_PROFILE) },
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
        composable(MenuRoutes.MEMBERS) {
            MembersScreen(onBack = { navController.popBackStack() })
        }
        composable(MenuRoutes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
