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
    fun `absent view_amounts defaults to TRUE in every module (ADR-039 backward compat)`() {
        // Pre-existing permission objects never carry view_amounts — amounts stay visible.
        val perms = json.decodeFromString<MemberPermissions>("""{ "booking": { "view": true } }""")
        assertThat(perms.booking.viewAmounts).isTrue()
        assertThat(perms.expenses.viewAmounts).isTrue()
        assertThat(perms.inventory.viewAmounts).isTrue()
        assertThat(perms.reports.viewAmounts).isTrue()
        // While every OTHER absent action stays false.
        assertThat(perms.booking.create).isFalse()
        assertThat(perms.reports.view).isFalse()
    }

    @Test
    fun `explicit view_amounts false survives a round-trip`() {
        val original =
            MemberPermissions.viewer().let {
                it.copy(
                    booking = it.booking.copy(viewAmounts = false),
                    reports = it.reports.copy(viewAmounts = false),
                )
            }
        val encoded = json.encodeToString(MemberPermissions.serializer(), original)
        assertThat(encoded).contains("view_amounts")
        val decoded = json.decodeFromString<MemberPermissions>(encoded)
        assertThat(decoded).isEqualTo(original)
        assertThat(decoded.booking.viewAmounts).isFalse()
        assertThat(decoded.reports.viewAmounts).isFalse()
        assertThat(decoded.expenses.viewAmounts).isTrue()
        assertThat(decoded.inventory.viewAmounts).isTrue()
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
