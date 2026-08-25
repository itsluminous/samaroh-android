package com.itsluminous.samaroh.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class PermissionsSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes the schema example shape`() {
        val payload =
            """
            {
              "booking":   { "view": true, "create": true, "edit": false, "delete": false,
                             "record_payment": true, "generate_invoice": true },
              "expenses":  { "view": true, "create": true, "edit": false, "delete": false,
                             "manage_parties": true },
              "inventory": { "view": true, "create": true, "edit": false, "delete": false,
                             "manage_master_items": false },
              "reports":   { "view": false },
              "settings":  { "manage_business": false, "manage_members": false,
                             "gcal_sync": false }
            }
            """.trimIndent()
        val perms = json.decodeFromString<MemberPermissions>(payload)
        assertThat(perms.booking.recordPayment).isTrue()
        assertThat(perms.booking.generateInvoice).isTrue()
        assertThat(perms.expenses.manageParties).isTrue()
        assertThat(perms.inventory.manageMasterItems).isFalse()
        assertThat(perms.reports.view).isFalse()
        assertThat(perms.settings.gcalSync).isFalse()
    }

    @Test
    fun `empty object means all actions denied`() {
        val perms = json.decodeFromString<MemberPermissions>("{}")
        assertThat(perms.booking.view).isFalse()
        assertThat(perms.expenses.create).isFalse()
        assertThat(perms.inventory.delete).isFalse()
        assertThat(perms.settings.manageMembers).isFalse()
    }

    @Test
    fun `round-trips through snake_case json`() {
        val original = MemberPermissions.manager()
        val encoded = json.encodeToString(MemberPermissions.serializer(), original)
        assertThat(encoded).contains("record_payment")
        assertThat(encoded).contains("manage_master_items")
        assertThat(json.decodeFromString<MemberPermissions>(encoded)).isEqualTo(original)
    }

    @Test
    fun `presets follow the spec matrix`() {
        val viewer = MemberPermissions.viewer()
        assertThat(viewer.booking.view).isTrue()
        assertThat(viewer.booking.create).isFalse()

        val staff = MemberPermissions.staff()
        assertThat(staff.inventory.create).isTrue()
        assertThat(staff.inventory.edit).isFalse()

        val manager = MemberPermissions.manager()
        assertThat(manager.booking.delete).isTrue()
        assertThat(manager.settings.manageMembers).isFalse()
        assertThat(manager.settings.manageBusiness).isFalse()
    }
}
