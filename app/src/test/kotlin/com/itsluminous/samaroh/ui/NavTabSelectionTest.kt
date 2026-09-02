package com.itsluminous.samaroh.ui

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE
import com.itsluminous.samaroh.feature.menu.MENU_ROUTE
import com.itsluminous.samaroh.feature.menu.SYNC_STATUS_ROUTE
import com.itsluminous.samaroh.feature.reports.REPORTS_ROUTE
import org.junit.Test

/**
 * Bottom-tab selection (ADR-042): hierarchy-based matching so the Menu tab stays
 * highlighted on its root-level subscreens (Reports, Sync status), never exact-route
 * comparison. Hierarchy lists are destination-outward, mirroring
 * `NavDestination.hierarchy` (destination, parent graphs…, root).
 */
class NavTabSelectionTest {
    @Test
    fun `a tab destination selects its own tab`() {
        assertThat(NavTabSelection.selectedTab(listOf(BOOKING_ROUTE))).isEqualTo(BOOKING_ROUTE)
        assertThat(NavTabSelection.selectedTab(listOf(EXPENSES_ROUTE))).isEqualTo(EXPENSES_ROUTE)
        assertThat(NavTabSelection.selectedTab(listOf(INVENTORY_ROUTE))).isEqualTo(INVENTORY_ROUTE)
    }

    @Test
    fun `the menu start destination selects the menu tab through its graph`() {
        // menu → menu_tab graph → root (route-less root omitted by mapNotNull).
        assertThat(NavTabSelection.selectedTab(listOf(MENU_ROUTE, MENU_TAB_ROUTE))).isEqualTo(MENU_TAB_ROUTE)
    }

    @Test
    fun `reports keeps the menu tab selected`() {
        assertThat(NavTabSelection.selectedTab(listOf(REPORTS_ROUTE, MENU_TAB_ROUTE))).isEqualTo(MENU_TAB_ROUTE)
    }

    @Test
    fun `sync status keeps the menu tab selected`() {
        assertThat(NavTabSelection.selectedTab(listOf(SYNC_STATUS_ROUTE, MENU_TAB_ROUTE))).isEqualTo(MENU_TAB_ROUTE)
    }

    @Test
    fun `onboarding selects no tab`() {
        assertThat(NavTabSelection.selectedTab(listOf("onboarding_signin", "onboarding"))).isNull()
    }

    @Test
    fun `empty hierarchy selects no tab`() {
        assertThat(NavTabSelection.selectedTab(emptyList())).isNull()
    }

    @Test
    fun `selection respects a custom tab set`() {
        // A hidden tab (§3 gate) never gets selected even if its route is in the hierarchy.
        assertThat(
            NavTabSelection.selectedTab(listOf(BOOKING_ROUTE), tabRoutes = listOf(EXPENSES_ROUTE, MENU_TAB_ROUTE)),
        ).isNull()
    }
}
