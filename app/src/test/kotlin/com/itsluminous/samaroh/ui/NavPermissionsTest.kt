package com.itsluminous.samaroh.ui

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingPermissions
import com.itsluminous.samaroh.core.model.ExpensesPermissions
import com.itsluminous.samaroh.core.model.InventoryPermissions
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE
import com.itsluminous.samaroh.feature.menu.MENU_ROUTE
import org.junit.Test

/** Tab-level §3 gate: bottom-nav tabs by member `view` permissions; Menu always stays. */
class NavPermissionsTest {
    @Test
    fun `owner sees all four tabs regardless of the permission object`() {
        val tabs = NavPermissions.visibleTabRoutes(isOwner = true, permissions = MemberPermissions())
        assertThat(tabs).containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, MENU_ROUTE).inOrder()
    }

    @Test
    fun `member with no permissions still gets the Menu tab`() {
        val tabs = NavPermissions.visibleTabRoutes(isOwner = false, permissions = MemberPermissions())
        assertThat(tabs).containsExactly(MENU_ROUTE)
    }

    @Test
    fun `viewer preset keeps booking expenses inventory and menu`() {
        val tabs = NavPermissions.visibleTabRoutes(isOwner = false, permissions = MemberPermissions.viewer())
        assertThat(tabs).containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, MENU_ROUTE).inOrder()
    }

    @Test
    fun `missing booking view drops only the booking tab`() {
        val tabs =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions =
                    MemberPermissions(
                        expenses = ExpensesPermissions(view = true),
                        inventory = InventoryPermissions(view = true),
                    ),
            )
        assertThat(tabs).containsExactly(EXPENSES_ROUTE, INVENTORY_ROUTE, MENU_ROUTE).inOrder()
    }

    @Test
    fun `missing expenses view drops only the expenses tab`() {
        val tabs =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions =
                    MemberPermissions(
                        booking = BookingPermissions(view = true),
                        inventory = InventoryPermissions(view = true),
                    ),
            )
        assertThat(tabs).containsExactly(BOOKING_ROUTE, INVENTORY_ROUTE, MENU_ROUTE).inOrder()
    }

    @Test
    fun `missing inventory view drops only the inventory tab`() {
        val tabs =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions =
                    MemberPermissions(
                        booking = BookingPermissions(view = true),
                        expenses = ExpensesPermissions(view = true),
                    ),
            )
        assertThat(tabs).containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE, MENU_ROUTE).inOrder()
    }

    @Test
    fun `write permissions without view do not surface a tab`() {
        // A malformed grant (create without view) must not leak the tab in.
        val tabs =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions = MemberPermissions(booking = BookingPermissions(create = true)),
            )
        assertThat(tabs).containsExactly(MENU_ROUTE)
    }

    @Test
    fun `first visible tab exists even with nothing granted`() {
        val tabs = NavPermissions.visibleTabRoutes(isOwner = false, permissions = MemberPermissions())
        assertThat(tabs).isNotEmpty()
        assertThat(tabs.first()).isEqualTo(MENU_ROUTE)
    }
}
