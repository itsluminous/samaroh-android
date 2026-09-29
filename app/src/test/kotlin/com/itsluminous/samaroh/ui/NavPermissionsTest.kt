package com.itsluminous.samaroh.ui

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingPermissions
import com.itsluminous.samaroh.core.model.ExpensesPermissions
import com.itsluminous.samaroh.core.model.FilesPermissions
import com.itsluminous.samaroh.core.model.InventoryPermissions
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.NotesPermissions
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.files.FILES_ROUTE
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE
import com.itsluminous.samaroh.feature.menu.MENU_ROUTE
import com.itsluminous.samaroh.feature.menu.SYNC_STATUS_ROUTE
import com.itsluminous.samaroh.feature.notes.NOTES_ROUTE
import com.itsluminous.samaroh.feature.reports.REPORTS_ROUTE
import org.junit.Test

/**
 * Tab-level §3 gate: bottom-bar tabs by member `view` permissions. Since ADR-087 the
 * bar holds MODULES only (Files took the Menu's slot) and the Menu sits behind the
 * title-bar kebab, so a hidden module simply shortens the bar.
 */
class NavPermissionsTest {
    @Test
    fun `owner sees every module in canonical order and no menu tab`() {
        val tabs = NavPermissions.visibleTabRoutes(isOwner = true, permissions = MemberPermissions())
        assertThat(tabs).containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, NOTES_ROUTE, FILES_ROUTE).inOrder()
        assertThat(tabs).doesNotContain(MENU_TAB_ROUTE)
    }

    @Test
    fun `files is the fifth bar tab for a full-permission owner (ADR-087)`() {
        val tabs = NavPermissions.visibleTabRoutes(isOwner = true, permissions = MemberPermissions())
        assertThat(tabs.last()).isEqualTo(FILES_ROUTE)
        assertThat(tabs).hasSize(5)
    }

    @Test
    fun `missing files view drops the files tab and nothing takes its place`() {
        val tabs =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions =
                    MemberPermissions(
                        booking = BookingPermissions(view = true),
                        expenses = ExpensesPermissions(view = true),
                        inventory = InventoryPermissions(view = true),
                        notes = NotesPermissions(view = true),
                    ),
            )
        assertThat(tabs).containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, NOTES_ROUTE).inOrder()
        assertThat(tabs).doesNotContain(MENU_TAB_ROUTE)
    }

    @Test
    fun `files view alone surfaces exactly the files tab`() {
        val tabs =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions = MemberPermissions(files = FilesPermissions(view = true)),
            )
        assertThat(tabs).containsExactly(FILES_ROUTE)
        assertThat(NavPermissions.startDestination(tabs)).isEqualTo(FILES_ROUTE)
        assertThat(NavPermissions.showsBottomBar(tabs)).isTrue()
    }

    @Test
    fun `member with no permissions gets no bar and starts on the menu`() {
        val tabs = NavPermissions.visibleTabRoutes(isOwner = false, permissions = MemberPermissions())
        assertThat(tabs).isEmpty()
        assertThat(NavPermissions.showsBottomBar(tabs)).isFalse()
        assertThat(NavPermissions.startDestination(tabs)).isEqualTo(MENU_TAB_ROUTE)
    }

    @Test
    fun `viewer preset keeps every module`() {
        val tabs = NavPermissions.visibleTabRoutes(isOwner = false, permissions = MemberPermissions.viewer())
        assertThat(tabs).containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, NOTES_ROUTE, FILES_ROUTE).inOrder()
    }

    @Test
    fun `missing booking view drops only the booking tab and moves the start destination`() {
        val tabs =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions =
                    MemberPermissions(
                        expenses = ExpensesPermissions(view = true),
                        inventory = InventoryPermissions(view = true),
                        notes = NotesPermissions(view = true),
                    ),
            )
        assertThat(tabs).containsExactly(EXPENSES_ROUTE, INVENTORY_ROUTE, NOTES_ROUTE).inOrder()
        assertThat(NavPermissions.startDestination(tabs)).isEqualTo(EXPENSES_ROUTE)
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
        assertThat(tabs).containsExactly(BOOKING_ROUTE, INVENTORY_ROUTE).inOrder()
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
        assertThat(tabs).containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE).inOrder()
    }

    @Test
    fun `notes view alone surfaces exactly the notes tab (ADR-077)`() {
        val tabs =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions = MemberPermissions(notes = NotesPermissions(view = true)),
            )
        assertThat(tabs).containsExactly(NOTES_ROUTE)
    }

    @Test
    fun `write permissions without view do not surface a tab`() {
        // A malformed grant (create/upload without view) must not leak the tab in.
        val tabs =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions =
                    MemberPermissions(
                        booking = BookingPermissions(create = true),
                        notes = NotesPermissions(create = true),
                        files = FilesPermissions(upload = true),
                    ),
            )
        assertThat(tabs).isEmpty()
    }

    @Test
    fun `all tab routes are exactly the module routes`() {
        assertThat(NavPermissions.allTabRoutes).isEqualTo(NavPermissions.moduleRoutes)
        assertThat(NavPermissions.allTabRoutes).doesNotContain(MENU_TAB_ROUTE)
    }

    // ---- title-bar kebab (ADR-087): the Menu's only entry point ----

    @Test
    fun `kebab shows on every module tab`() {
        NavPermissions.moduleRoutes.forEach { route ->
            assertThat(NavPermissions.showsMenuKebab(listOf(route))).isTrue()
        }
    }

    @Test
    fun `kebab hides inside the menu graph and its subscreens`() {
        assertThat(NavPermissions.showsMenuKebab(listOf(MENU_ROUTE, MENU_TAB_ROUTE))).isFalse()
        assertThat(NavPermissions.showsMenuKebab(listOf(REPORTS_ROUTE, MENU_TAB_ROUTE))).isFalse()
        assertThat(NavPermissions.showsMenuKebab(listOf(SYNC_STATUS_ROUTE, MENU_TAB_ROUTE))).isFalse()
    }
}
