package com.itsluminous.samaroh.ui

import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE
import com.itsluminous.samaroh.feature.menu.MENU_ROUTE

/**
 * Tab-level §3 gate: a member without a module's `view` permission does not get that
 * bottom-nav tab at all (hidden, not greyed). The Menu tab always stays — it hosts
 * Settings/About which every member may open. Owners (and the signed-out/offline
 * owner-mode default) see all four tabs.
 */
object NavPermissions {
    /** Every tab in canonical bottom-bar order. */
    val allTabRoutes: List<String> = listOf(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, MENU_ROUTE)

    /**
     * The tabs visible to a member with [permissions]; owners see everything.
     * Never empty — MENU_ROUTE is unconditional, so a first visible tab always exists.
     */
    fun visibleTabRoutes(
        isOwner: Boolean,
        permissions: MemberPermissions,
    ): List<String> =
        buildList {
            if (isOwner || permissions.booking.view) add(BOOKING_ROUTE)
            if (isOwner || permissions.expenses.view) add(EXPENSES_ROUTE)
            if (isOwner || permissions.inventory.view) add(INVENTORY_ROUTE)
            add(MENU_ROUTE)
        }
}
