package com.itsluminous.samaroh.ui

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.files.FILES_ROUTE
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE
import com.itsluminous.samaroh.feature.menu.MENU_ROUTE
import com.itsluminous.samaroh.feature.menu.SYNC_STATUS_ROUTE
import com.itsluminous.samaroh.feature.reports.REPORTS_ROUTE
import org.junit.Test

/**
 * Bottom-tab selection (ADR-042): hierarchy-based matching, never exact-route
 * comparison. Since ADR-087 the Menu graph (and its root-level subscreens Reports and
 * Sync status) is pushed over a tab from the title-bar kebab and belongs to NO tab, so
 * nothing highlights there. Hierarchy lists are destination-outward, mirroring
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
    fun `the menu graph selects no tab (ADR-087)`() {
        // menu → menu_tab graph → root (route-less root omitted by mapNotNull).
        assertThat(NavTabSelection.selectedTab(listOf(MENU_ROUTE, MENU_TAB_ROUTE))).isNull()
    }

    @Test
    fun `reports under the menu graph selects no tab`() {
        assertThat(NavTabSelection.selectedTab(listOf(REPORTS_ROUTE, MENU_TAB_ROUTE))).isNull()
    }

    @Test
    fun `sync status under the menu graph selects no tab`() {
        assertThat(NavTabSelection.selectedTab(listOf(SYNC_STATUS_ROUTE, MENU_TAB_ROUTE))).isNull()
    }

    @Test
    fun `files destination selects the files tab`() {
        assertThat(NavTabSelection.selectedTab(listOf(FILES_ROUTE))).isEqualTo(FILES_ROUTE)
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
            NavTabSelection.selectedTab(listOf(BOOKING_ROUTE), tabRoutes = listOf(EXPENSES_ROUTE, FILES_ROUTE)),
        ).isNull()
    }
}
