package com.itsluminous.samaroh.core.auth

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.auth.permissions.PermissionMatrix
import com.itsluminous.samaroh.core.model.MemberPermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File

/**
 * Verifies `core:model.MemberPermissions` stays an exact mirror of the canonical
 * JSON Schema (`shared/permissions/permissions-schema.json`, §3): same modules, same
 * actions, lossless round-trip. Drift here would silently desync app-layer checks
 * from the RLS layer.
 */
class PermissionsSchemaRoundTripTest {
    private val json = Json { encodeDefaults = true }

    private fun schemaModules(): Map<String, Set<String>> {
        val path = System.getProperty("samaroh.permissionsSchema")
        assertThat(path).isNotNull()
        val schema = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        val properties = schema.getValue("properties").jsonObject
        return properties.mapValues { (_, module) ->
            module.jsonObject
                .getValue("properties")
                .jsonObject.keys
        }
    }

    private fun modelModules(permissions: MemberPermissions): Map<String, Set<String>> =
        json
            .encodeToJsonElement(MemberPermissions.serializer(), permissions)
            .jsonObject
            .mapValues { (_, module) -> module.jsonObject.keys }

    @Test
    fun `model modules and actions exactly match the canonical schema`() {
        val schema = schemaModules()
        val model = modelModules(MemberPermissions())

        assertThat(model.keys).isEqualTo(schema.keys)
        schema.forEach { (module, actions) ->
            assertThat(model.getValue(module)).isEqualTo(actions)
        }
    }

    @Test
    fun `round-trip is lossless for every preset and full access`() {
        val samples =
            listOf(
                MemberPermissions(),
                MemberPermissions.viewer(),
                MemberPermissions.staff(),
                MemberPermissions.manager(),
                PermissionMatrix.fullAccess(),
            )
        samples.forEach { original ->
            val encoded = json.encodeToString(MemberPermissions.serializer(), original)
            val decoded = json.decodeFromString(MemberPermissions.serializer(), encoded)
            assertThat(decoded).isEqualTo(original)
        }
    }

    @Test
    fun `schema example shape from the spec decodes to the expected grants`() {
        // The exact permissions JSON example from spec §3.
        val specExample =
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
        val decoded = json.decodeFromString(MemberPermissions.serializer(), specExample)
        assertThat(decoded.booking.recordPayment).isTrue()
        assertThat(decoded.booking.generateInvoice).isTrue()
        assertThat(decoded.expenses.manageParties).isTrue()
        assertThat(decoded.inventory.manageMasterItems).isFalse()
        assertThat(decoded.reports.view).isFalse()
        assertThat(decoded.settings.gcalSync).isFalse()
        // And re-encoding preserves it (round trip through the wire shape).
        val reEncoded = json.encodeToString(MemberPermissions.serializer(), decoded)
        assertThat(json.decodeFromString(MemberPermissions.serializer(), reEncoded)).isEqualTo(decoded)
    }

    @Test
    fun `absent actions default to false (partial json from older rows)`() {
        val partial = """{ "booking": { "view": true } }"""
        val decoded = Json.decodeFromString(MemberPermissions.serializer(), partial)
        assertThat(decoded.booking.view).isTrue()
        assertThat(decoded.booking.create).isFalse()
        assertThat(decoded.expenses.view).isFalse()
        assertThat(decoded.settings.manageMembers).isFalse()
    }
}
