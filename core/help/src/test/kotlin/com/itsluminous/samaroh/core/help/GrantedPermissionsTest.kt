package com.itsluminous.samaroh.core.help

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.samaroh.core.model.FilesPermissions
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.NotesPermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File

/** Guide `permission` gates resolve against [MemberPermissions] by wire name (ADR-108). */
class GrantedPermissionsTest {
    @Test
    fun `owner holds everything including unknown keys`() {
        assertThat(GrantedPermissions.OWNER.holds("booking.record_payment")).isTrue()
        assertThat(GrantedPermissions.OWNER.holds("made.up")).isTrue()
        assertThat(GrantedPermissions.of(true, MemberPermissions()).holds("settings.manage_members")).isTrue()
    }

    @Test
    fun `no gate is always granted`() {
        assertThat(GrantedPermissions.of(false, MemberPermissions()).holds(null)).isTrue()
    }

    @Test
    fun `member grants resolve by section and capability wire names`() {
        val staff = GrantedPermissions.of(false, MemberPermissions.staff())
        assertThat(staff.holds("booking.view")).isTrue()
        assertThat(staff.holds("booking.record_payment")).isTrue()
        assertThat(staff.holds("booking.generate_invoice")).isFalse()
        assertThat(staff.holds("expenses.manage_parties")).isFalse()
        assertThat(staff.holds("inventory.manage_master_items")).isFalse()
        assertThat(staff.holds("settings.manage_members")).isFalse()
        assertThat(staff.holds("reports.view")).isFalse()
        // Defaults that are TRUE in the model stay true.
        assertThat(staff.holds("booking.view_amounts")).isTrue()
    }

    @Test
    fun `unknown keys fail closed`() {
        val manager = GrantedPermissions.of(false, MemberPermissions.manager())
        assertThat(manager.holds("booking.fly")).isFalse()
        assertThat(manager.holds("nosuchsection.view")).isFalse()
        assertThat(manager.holds("booking")).isFalse()
    }

    @Test
    fun `inherited capabilities resolve to their effective value`() {
        val inherits = GrantedPermissions.of(false, MemberPermissions(files = FilesPermissions(view = true, upload = true)))
        assertThat(inherits.holds("files.manage_folders")).isTrue()
        val revoked =
            GrantedPermissions.of(
                false,
                MemberPermissions(files = FilesPermissions(view = true, upload = true, manageFolders = false)),
            )
        assertThat(revoked.holds("files.manage_folders")).isFalse()
        val notes = GrantedPermissions.of(false, MemberPermissions(notes = NotesPermissions(view = true, edit = true)))
        assertThat(notes.holds("notes.view_checklists")).isTrue()
        assertThat(notes.holds("notes.toggle_checklist")).isTrue()
    }

    @Test
    fun `every permission the guide gates on exists in the shared permissions schema and is resolvable`() {
        val schema = Json.parseToJsonElement(File(SharedHelpFiles.permissionsDir, "permissions-schema.json").readText()).jsonObject
        val sections = schema["properties"]!!.jsonObject
        val guide = SharedHelpFiles.guide()
        val used = guide.screens.flatMap { s -> listOfNotNull(s.permission) + s.tasks.mapNotNull { it.permission } }.toSet()
        assertThat(used).isNotEmpty()
        val manager = GrantedPermissions.of(false, MemberPermissions.manager())
        for (key in used) {
            val (section, capability) = key.split('.', limit = 2)
            val caps =
                sections[section]
                    ?.jsonObject
                    ?.get("properties")
                    ?.jsonObject
                    ?.keys
            assertWithMessage("'$key' not in permissions-schema.json").that(caps).isNotNull()
            assertWithMessage("'$key' not in permissions-schema.json").that(caps!!).contains(capability)
            // The manager preset holds every module capability except settings.*; resolving it
            // proves the key maps onto a MemberPermissions field (a typo would fail closed).
            if (section != "settings") assertWithMessage("'$key' unresolvable for manager").that(manager.holds(key)).isTrue()
        }
    }
}
