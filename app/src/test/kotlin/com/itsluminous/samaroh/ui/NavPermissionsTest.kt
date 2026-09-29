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
import com.itsluminous.samaroh.feature.notes.NOTES_ROUTE
import org.junit.Test

/** Tab-level §3 gate: bottom-nav tabs by member `view` permissions; Menu always stays. */
class NavPermissionsTest {
    @Test
    fun `owner sees every module regardless of the permission object`() {
        val tabs = NavPermissions.visibleTabRoutes(isOwner = true, permissions = MemberPermissions())
        assertThat(tabs).containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, NOTES_ROUTE, FILES_ROUTE, MENU_TAB_ROUTE).inOrder()
    }

    // ---- bottom-bar overflow rule (ADR-085, design D15): 4 modules + Menu ----

    @Test
    fun `full-permission owner gets four modules in the bar and Files overflows into More`() {
        val visible = NavPermissions.visibleTabRoutes(isOwner = true, permissions = MemberPermissions())
        assertThat(NavPermissions.barTabRoutes(visible))
            .containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, NOTES_ROUTE, MENU_TAB_ROUTE)
            .inOrder()
        assertThat(NavPermissions.overflowModuleRoutes(visible)).containsExactly(FILES_ROUTE)
    }

    @Test
    fun `a member without inventory sees Files in the bar and nothing overflows`() {
        val visible =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions =
                    MemberPermissions(
                        booking = BookingPermissions(view = true),
                        expenses = ExpensesPermissions(view = true),
                        notes = NotesPermissions(view = true),
                        files = FilesPermissions(view = true),
                    ),
            )
        assertThat(NavPermissions.barTabRoutes(visible))
            .containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE, NOTES_ROUTE, FILES_ROUTE, MENU_TAB_ROUTE)
            .inOrder()
        assertThat(NavPermissions.overflowModuleRoutes(visible)).isEmpty()
    }

    @Test
    fun `files view alone surfaces exactly the files and menu tabs`() {
        val visible =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions = MemberPermissions(files = FilesPermissions(view = true)),
            )
        assertThat(visible).containsExactly(FILES_ROUTE, MENU_TAB_ROUTE).inOrder()
        assertThat(NavPermissions.barTabRoutes(visible)).containsExactly(FILES_ROUTE, MENU_TAB_ROUTE).inOrder()
    }

    @Test
    fun `menu is always the last bar tab even with no modules`() {
        assertThat(NavPermissions.barTabRoutes(listOf(MENU_TAB_ROUTE))).containsExactly(MENU_TAB_ROUTE)
    }

    @Test
    fun `member with no permissions still gets the Menu tab`() {
        val tabs = NavPermissions.visibleTabRoutes(isOwner = false, permissions = MemberPermissions())
        assertThat(tabs).containsExactly(MENU_TAB_ROUTE)
    }

    @Test
    fun `viewer preset keeps every module and menu`() {
        val tabs = NavPermissions.visibleTabRoutes(isOwner = false, permissions = MemberPermissions.viewer())
        assertThat(tabs).containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, NOTES_ROUTE, FILES_ROUTE, MENU_TAB_ROUTE).inOrder()
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
                        notes = NotesPermissions(view = true),
                    ),
            )
        assertThat(tabs).containsExactly(EXPENSES_ROUTE, INVENTORY_ROUTE, NOTES_ROUTE, MENU_TAB_ROUTE).inOrder()
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
        assertThat(tabs).containsExactly(BOOKING_ROUTE, INVENTORY_ROUTE, MENU_TAB_ROUTE).inOrder()
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
        assertThat(tabs).containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE, MENU_TAB_ROUTE).inOrder()
    }

    @Test
    fun `notes view alone surfaces exactly the notes and menu tabs (ADR-077)`() {
        val tabs =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions = MemberPermissions(notes = NotesPermissions(view = true)),
            )
        assertThat(tabs).containsExactly(NOTES_ROUTE, MENU_TAB_ROUTE).inOrder()
    }

    @Test
    fun `missing notes view hides the notes tab (ADR-077)`() {
        val tabs =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions =
                    MemberPermissions(
                        booking = BookingPermissions(view = true),
                        expenses = ExpensesPermissions(view = true),
                        inventory = InventoryPermissions(view = true),
                    ),
            )
        assertThat(tabs).containsExactly(BOOKING_ROUTE, EXPENSES_ROUTE, INVENTORY_ROUTE, MENU_TAB_ROUTE).inOrder()
    }

    @Test
    fun `write permissions without view do not surface a tab`() {
        // A malformed grant (create without view) must not leak the tab in.
        val tabs =
            NavPermissions.visibleTabRoutes(
                isOwner = false,
                permissions =
                    MemberPermissions(
                        booking = BookingPermissions(create = true),
                        notes = NotesPermissions(create = true),
                    ),
            )
        assertThat(tabs).containsExactly(MENU_TAB_ROUTE)
    }

    @Test
    fun `first visible tab exists even with nothing granted`() {
        val tabs = NavPermissions.visibleTabRoutes(isOwner = false, permissions = MemberPermissions())
        assertThat(tabs).isNotEmpty()
        assertThat(tabs.first()).isEqualTo(MENU_TAB_ROUTE)
    }
}
