package com.itsluminous.samaroh.ui

import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE

/**
 * Root route of the Menu TAB's nested navigation graph (ADR-042). The Menu tab is a
 * `navigation()` graph — start destination `MENU_ROUTE` plus the root-level Menu
 * subscreens (Reports, Sync status) — so `currentDestination.hierarchy` matching keeps
 * the Menu tab highlighted on every subscreen. The other three tabs are single
 * destinations hosting their own internal NavHosts, which hierarchy matching covers by
 * the destination itself.
 */
const val MENU_TAB_ROUTE = "menu_tab"

/**
 * Tab-level §3 gate: a member without a module's `view` permission does not get that
 * bottom-nav tab at all (hidden, not greyed). The Menu tab always stays — it hosts
 * Settings/About which every member may open. Owners (and the signed-out/offline
 * owner-mode default) see all four tabs.
 */
object NavPermissions {
    /** Every tab in canonical bottom-bar order. */
    val allTabRoutes: List<String> = listOf(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, MENU_TAB_ROUTE)

    /**
     * The tabs visible to a member with [permissions]; owners see everything.
     * Never empty — [MENU_TAB_ROUTE] is unconditional, so a first visible tab always exists.
     */
    fun visibleTabRoutes(
        isOwner: Boolean,
        permissions: MemberPermissions,
    ): List<String> =
        buildList {
            if (isOwner || permissions.booking.view) add(BOOKING_ROUTE)
            if (isOwner || permissions.expenses.view) add(EXPENSES_ROUTE)
            if (isOwner || permissions.inventory.view) add(INVENTORY_ROUTE)
            add(MENU_TAB_ROUTE)
        }
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
