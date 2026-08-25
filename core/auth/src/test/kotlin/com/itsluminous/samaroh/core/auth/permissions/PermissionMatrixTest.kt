package com.itsluminous.samaroh.core.auth.permissions

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.MemberPermissions
import org.junit.Test

/** State logic behind [PermissionMatrixEditor]: grouping, toggling and the three presets (§3). */
class PermissionMatrixTest {
    @Test
    fun `groups are ordered by tab and cover every schema action`() {
        val groups = PermissionMatrix.groups(MemberPermissions())

        assertThat(groups.map { it.moduleKey })
            .containsExactly("booking", "expenses", "inventory", "reports", "settings")
            .inOrder()
        assertThat(groups.first { it.moduleKey == "booking" }.toggles.map { it.actionKey })
            .containsExactly("view", "create", "edit", "delete", "record_payment", "generate_invoice")
        assertThat(groups.first { it.moduleKey == "expenses" }.toggles.map { it.actionKey })
            .containsExactly("view", "create", "edit", "delete", "manage_parties")
        assertThat(groups.first { it.moduleKey == "inventory" }.toggles.map { it.actionKey })
            .containsExactly("view", "create", "edit", "delete", "manage_master_items")
        assertThat(groups.first { it.moduleKey == "reports" }.toggles.map { it.actionKey })
            .containsExactly("view")
        assertThat(groups.first { it.moduleKey == "settings" }.toggles.map { it.actionKey })
            .containsExactly("manage_business", "manage_members", "gcal_sync")
    }

    @Test
    fun `toggle flips exactly one action and nothing else`() {
        val toggled = PermissionMatrix.toggle(MemberPermissions(), "booking", "record_payment")

        assertThat(toggled.booking.recordPayment).isTrue()
        assertThat(toggled.copy(booking = toggled.booking.copy(recordPayment = false)))
            .isEqualTo(MemberPermissions())

        // Toggling again restores the original.
        assertThat(PermissionMatrix.toggle(toggled, "booking", "record_payment"))
            .isEqualTo(MemberPermissions())
    }

    @Test
    fun `toggle with unknown module or action is a no-op`() {
        val base = MemberPermissions.staff()
        assertThat(PermissionMatrix.toggle(base, "nonexistent", "view")).isEqualTo(base)
        assertThat(PermissionMatrix.toggle(base, "booking", "nonexistent")).isEqualTo(base)
    }

    @Test
    fun `presets produce the canonical preset permission sets`() {
        assertThat(PermissionPreset.VIEWER.permissions()).isEqualTo(MemberPermissions.viewer())
        assertThat(PermissionPreset.STAFF.permissions()).isEqualTo(MemberPermissions.staff())
        assertThat(PermissionPreset.MANAGER.permissions()).isEqualTo(MemberPermissions.manager())

        // Viewer = all view, nothing else.
        val viewerGroups = PermissionMatrix.groups(MemberPermissions.viewer())
        viewerGroups.flatMap { it.toggles }.forEach { toggle ->
            assertThat(toggle.enabled).isEqualTo(toggle.actionKey == "view")
        }

        // Manager = everything except settings/members.
        val manager = MemberPermissions.manager()
        assertThat(manager.settings.manageBusiness).isFalse()
        assertThat(manager.settings.manageMembers).isFalse()
        assertThat(manager.settings.gcalSync).isFalse()
        assertThat(manager.booking.delete).isTrue()
    }

    @Test
    fun `matchingPreset detects presets and returns null for custom mixes`() {
        assertThat(PermissionMatrix.matchingPreset(MemberPermissions.viewer())).isEqualTo(PermissionPreset.VIEWER)
        assertThat(PermissionMatrix.matchingPreset(MemberPermissions.staff())).isEqualTo(PermissionPreset.STAFF)
        assertThat(PermissionMatrix.matchingPreset(MemberPermissions.manager())).isEqualTo(PermissionPreset.MANAGER)

        val custom = PermissionMatrix.toggle(MemberPermissions.viewer(), "booking", "create")
        assertThat(PermissionMatrix.matchingPreset(custom)).isNull()
    }

    @Test
    fun `preset then manual toggle preserves the rest of the preset`() {
        val staffPlusEdit = PermissionMatrix.toggle(MemberPermissions.staff(), "booking", "edit")
        assertThat(staffPlusEdit.booking.edit).isTrue()
        assertThat(staffPlusEdit.booking.view).isTrue()
        assertThat(staffPlusEdit.booking.create).isTrue()
        assertThat(staffPlusEdit.booking.recordPayment).isTrue()
        assertThat(staffPlusEdit.expenses.create).isTrue()
    }

    @Test
    fun `fullAccess grants every action in every group`() {
        PermissionMatrix.groups(PermissionMatrix.fullAccess()).flatMap { it.toggles }.forEach { toggle ->
            assertThat(toggle.enabled).isTrue()
        }
    }
}
