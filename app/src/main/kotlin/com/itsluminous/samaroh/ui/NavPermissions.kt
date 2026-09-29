package com.itsluminous.samaroh.ui

import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.files.FILES_ROUTE
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE
import com.itsluminous.samaroh.feature.notes.NOTES_ROUTE

/**
 * Root route of the Menu TAB's nested navigation graph (ADR-042). The Menu tab is a
 * `navigation()` graph — start destination `MENU_ROUTE` plus the root-level Menu
 * subscreens (Reports, Sync status) — so `currentDestination.hierarchy` matching keeps
 * the Menu tab highlighted on every subscreen. The other tabs are single destinations
 * hosting their own internal NavHosts, which hierarchy matching covers by the
 * destination itself.
 */
const val MENU_TAB_ROUTE = "menu_tab"

/**
 * Tab-level §3 gate: a member without a module's `view` permission does not get that
 * module tab at all (hidden, not greyed). The Menu tab always stays — it hosts
 * Settings/About which every member may open. Owners (and the signed-out/offline
 * owner-mode default) see every module.
 *
 * Bottom-bar OVERFLOW rule (ADR-085, design D15): Material 3 allows five bar items, so
 * at most [BAR_MODULE_CAP] visible modules sit in the bar (in canonical order) + Menu;
 * any further visible module overflows into a "More" section at the top of the Menu tab
 * (and into menu search). The module remains a real top-level destination either way.
 */
object NavPermissions {
    /** Modules the bottom bar can hold besides Menu (Material 3's 5-item cap). */
    const val BAR_MODULE_CAP = 4

    /** Every module tab in canonical order (Menu excluded). */
    val moduleRoutes: List<String> = listOf(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, NOTES_ROUTE, FILES_ROUTE)

    /** Every tab in canonical bottom-bar order, Menu last. */
    val allTabRoutes: List<String> = moduleRoutes + MENU_TAB_ROUTE

    /**
     * The module tabs + Menu visible to a member with [permissions]; owners see everything.
     * Never empty — [MENU_TAB_ROUTE] is unconditional, so a first visible tab always exists.
     * This is the full set of reachable tabs; split it with [barTabRoutes]/[overflowModuleRoutes].
     */
    fun visibleTabRoutes(
        isOwner: Boolean,
        permissions: MemberPermissions,
    ): List<String> =
        buildList {
            if (isOwner || permissions.booking.view) add(BOOKING_ROUTE)
            if (isOwner || permissions.expenses.view) add(EXPENSES_ROUTE)
            if (isOwner || permissions.inventory.view) add(INVENTORY_ROUTE)
            if (isOwner || permissions.notes.view) add(NOTES_ROUTE)
            if (isOwner || permissions.files.view) add(FILES_ROUTE)
            add(MENU_TAB_ROUTE)
        }

    /** The tabs rendered in the bottom bar: the first [BAR_MODULE_CAP] visible modules + Menu. */
    fun barTabRoutes(visibleTabs: List<String>): List<String> =
        visibleTabs.filter { it != MENU_TAB_ROUTE }.take(BAR_MODULE_CAP) + MENU_TAB_ROUTE

    /** Visible modules past the bar cap — listed in the Menu tab's "More" section. */
    fun overflowModuleRoutes(visibleTabs: List<String>): List<String> = visibleTabs.filter { it != MENU_TAB_ROUTE }.drop(BAR_MODULE_CAP)
}

/**
 * Pure bottom-tab selection (ADR-042): the tab whose route appears anywhere in the
 * current destination's PARENT-GRAPH HIERARCHY — never an exact-route comparison, so a
 * tab stays highlighted on every destination nested under it (Menu → Reports/Sync
 * status via the [MENU_TAB_ROUTE] graph; the other tabs via their own destination).
 */
object NavTabSelection {
    /**
     * The selected tab for a destination whose hierarchy carries [hierarchyRoutes]
     * (destination-outward, i.e. `NavDestination.hierarchy` order), or null when the
     * destination belongs to no tab (e.g. onboarding).
     */
    fun selectedTab(
        hierarchyRoutes: List<String>,
        tabRoutes: Collection<String> = NavPermissions.allTabRoutes,
    ): String? = hierarchyRoutes.firstOrNull { it in tabRoutes }
}
