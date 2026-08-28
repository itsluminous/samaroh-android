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
            .containsExactly("view", "view_amounts", "create", "edit", "delete", "record_payment", "generate_invoice")
        assertThat(groups.first { it.moduleKey == "expenses" }.toggles.map { it.actionKey })
            .containsExactly("view", "view_amounts", "create", "edit", "delete", "manage_parties")
        assertThat(groups.first { it.moduleKey == "inventory" }.toggles.map { it.actionKey })
            .containsExactly("view", "view_amounts", "create", "edit", "delete", "manage_master_items")
        assertThat(groups.first { it.moduleKey == "reports" }.toggles.map { it.actionKey })
            .containsExactly("view", "view_amounts")
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
    fun `view_amounts starts enabled on every preset and toggles off per module`() {
        // ADR-039: all presets leave the default-true view_amounts untouched.
        listOf(MemberPermissions.viewer(), MemberPermissions.staff(), MemberPermissions.manager()).forEach { preset ->
            assertThat(preset.booking.viewAmounts).isTrue()
            assertThat(preset.expenses.viewAmounts).isTrue()
            assertThat(preset.inventory.viewAmounts).isTrue()
            assertThat(preset.reports.viewAmounts).isTrue()
        }

        // The owner toggles it off per module through the matrix, wire key `view_amounts`.
        val masked = PermissionMatrix.toggle(MemberPermissions.viewer(), "booking", "view_amounts")
        assertThat(masked.booking.viewAmounts).isFalse()
        assertThat(masked.expenses.viewAmounts).isTrue()
        assertThat(PermissionMatrix.toggle(masked, "booking", "view_amounts")).isEqualTo(MemberPermissions.viewer())
    }

    @Test
    fun `presets produce the canonical preset permission sets`() {
        assertThat(PermissionPreset.VIEWER.permissions()).isEqualTo(MemberPermissions.viewer())
        assertThat(PermissionPreset.STAFF.permissions()).isEqualTo(MemberPermissions.staff())
        assertThat(PermissionPreset.MANAGER.permissions()).isEqualTo(MemberPermissions.manager())

        // Viewer = all view (+ default-true view_amounts), nothing else.
        val viewerGroups = PermissionMatrix.groups(MemberPermissions.viewer())
        viewerGroups.flatMap { it.toggles }.forEach { toggle ->
            assertThat(toggle.enabled).isEqualTo(toggle.actionKey == "view" || toggle.actionKey == "view_amounts")
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
