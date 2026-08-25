package com.itsluminous.samaroh.feature.menu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.samaroh.core.designsystem.component.PlaceholderScreen
import com.itsluminous.samaroh.core.i18n.R

/** Route of the Menu tab's start destination. */
const val MENU_ROUTE = "menu"

/**
 * Menu feature graph (Wave 0 skeleton — W1-F implements Settings incl. the language
 * switcher, Members, and About; Reports is wired from :feature:reports).
 */
fun NavGraphBuilder.menuGraph() {
    composable(MENU_ROUTE) {
        PlaceholderScreen(featureNameRes = R.string.common_nav_menu, icon = Icons.Filled.Menu)
    }
}
