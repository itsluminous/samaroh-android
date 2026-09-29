package com.itsluminous.samaroh.ui

import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.files.FILES_ROUTE
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE
import com.itsluminous.samaroh.feature.notes.NOTES_ROUTE

/**
 * Root route of the Menu's nested navigation graph (ADR-042/ADR-087). The Menu is a
 * `navigation()` graph — start destination `MENU_ROUTE` plus the root-level Menu
 * subscreens (Reports, Sync status). Since ADR-087 it is NOT a bottom-bar tab: the
 * title-bar kebab pushes it on top of the current tab, so its whole hierarchy selects
 * no bar item and system back returns to the tab it was opened from.
 */
const val MENU_TAB_ROUTE = "menu_tab"

/**
 * Tab-level §3 gate: a member without a module's `view` permission does not get that
 * module tab at all (hidden, not greyed — the bar simply has one fewer item). Owners
 * (and the signed-out/offline owner-mode default) see every module.
 *
 * Bottom bar = the visible MODULES only (ADR-087): Booking, Expenses, Inventory, Notes,
 * Files — five at most, exactly Material 3's bar cap, so nothing overflows any more.
 * The Menu lives behind the title-bar kebab (right of the sync icon) and is reachable
 * by every member, permissions or not; with NO visible module the shell starts there
 * and renders no bar at all.
 */
object NavPermissions {
    /** Every module tab in canonical bottom-bar order. */
    val moduleRoutes: List<String> = listOf(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, NOTES_ROUTE, FILES_ROUTE)

    /** Every bottom-bar tab (alias of [moduleRoutes] — the Menu is not a tab, ADR-087). */
    val allTabRoutes: List<String> = moduleRoutes

    /**
     * The module tabs visible to a member with [permissions]; owners see everything.
     * May be EMPTY (a member with no `view` grant) — the shell then starts on the Menu
     * ([startDestination]) and hides the bottom bar.
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
        }

    /** The first visible tab, or the Menu graph when the member can view no module. */
    fun startDestination(visibleTabs: List<String>): String = visibleTabs.firstOrNull() ?: MENU_TAB_ROUTE

    /** Whether the bottom bar renders at all: only when there is at least one tab. */
    fun showsBottomBar(visibleTabs: List<String>): Boolean = visibleTabs.isNotEmpty()

    /**
     * Whether the title-bar kebab (opens the Menu) renders for a destination whose
     * hierarchy carries [hierarchyRoutes]: everywhere in the signed-in shell EXCEPT
     * inside the Menu graph itself (it is already open — a second push would stack it).
     */
    fun showsMenuKebab(hierarchyRoutes: List<String>): Boolean = MENU_TAB_ROUTE !in hierarchyRoutes
}

/**
 * Pure bottom-tab selection (ADR-042): the tab whose route appears anywhere in the
 * current destination's PARENT-GRAPH HIERARCHY — never an exact-route comparison, so a
 * tab stays highlighted on every destination nested under it. The Menu graph and its
 * subscreens (Reports, Sync status) belong to no tab since ADR-087 → null.
 */
object NavTabSelection {
    /**
     * The selected tab for a destination whose hierarchy carries [hierarchyRoutes]
     * (destination-outward, i.e. `NavDestination.hierarchy` order), or null when the
     * destination belongs to no tab (e.g. onboarding, the Menu).
     */
    fun selectedTab(
        hierarchyRoutes: List<String>,
        tabRoutes: Collection<String> = NavPermissions.allTabRoutes,
    ): String? = hierarchyRoutes.firstOrNull { it in tabRoutes }
}
