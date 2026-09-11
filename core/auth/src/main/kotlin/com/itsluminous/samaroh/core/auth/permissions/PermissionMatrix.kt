package com.itsluminous.samaroh.core.auth.permissions

import com.itsluminous.samaroh.core.model.BookingPermissions
import com.itsluminous.samaroh.core.model.ExpensesPermissions
import com.itsluminous.samaroh.core.model.InventoryPermissions
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.NotesPermissions
import com.itsluminous.samaroh.core.model.ReportsPermissions
import com.itsluminous.samaroh.core.model.SettingsPermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** One toggle row: `actionKey` is the schema/wire action name (e.g. `record_payment`). */
data class PermissionToggle(
    val actionKey: String,
    val enabled: Boolean,
)

/** One matrix group: `moduleKey` is the schema/wire module name (rows grouped by tab, §3). */
data class PermissionGroup(
    val moduleKey: String,
    val toggles: List<PermissionToggle>,
)

/** The three quick presets of the owner grant UX (§3). */
enum class PermissionPreset {
    VIEWER,
    STAFF,
    MANAGER,
    ;

    fun permissions(): MemberPermissions =
        when (this) {
            VIEWER -> MemberPermissions.viewer()
            STAFF -> MemberPermissions.staff()
            MANAGER -> MemberPermissions.manager()
        }
}

/**
 * Pure logic behind the permission matrix editor. Works on the JSON projection of
 * [MemberPermissions], so module/action keys are BY CONSTRUCTION the exact
 * `@SerialName`s that `shared/permissions/permissions-schema.json` defines — the matrix
 * can never drift from the wire shape.
 */
object PermissionMatrix {
    private val json = Json { encodeDefaults = true }

    /**
     * Inheriting actions (schema/migration 007, ADR-082): an ABSENT (JSON null) value
     * displays and toggles from its parent action's value — the exact
     * `coalesce(child, parent, false)` normalization the DB applies.
     */
    private val inheritsFrom = mapOf("view_checklists" to "view", "toggle_checklist" to "edit")

    private fun effective(
        module: JsonObject,
        actionKey: String,
        value: JsonElement,
    ): Boolean =
        (value as? JsonPrimitive)?.booleanOrNull
            ?: inheritsFrom[actionKey]?.let { parent -> (module[parent] as? JsonPrimitive)?.booleanOrNull }
            ?: false

    /**
     * Groups in tab order with every action toggle, reflecting [permissions]. An
     * inheriting action that is unset renders its NORMALIZED (inherited) value, so the
     * matrix always shows what the member can actually do.
     */
    fun groups(permissions: MemberPermissions): List<PermissionGroup> =
        json
            .encodeToJsonElement(MemberPermissions.serializer(), permissions)
            .jsonObject
            .map { (module, actions) ->
                PermissionGroup(
                    moduleKey = module,
                    toggles =
                        actions.jsonObject.map { (action, value) ->
                            PermissionToggle(action, effective(actions.jsonObject, action, value))
                        },
                )
            }

    /**
     * Returns [permissions] with the [moduleKey]/[actionKey] toggle flipped. Flipping
     * an unset inheriting action writes the EXPLICIT negation of its inherited value —
     * from then on that member's key no longer follows the parent toggle.
     */
    fun toggle(
        permissions: MemberPermissions,
        moduleKey: String,
        actionKey: String,
    ): MemberPermissions {
        val root = json.encodeToJsonElement(MemberPermissions.serializer(), permissions).jsonObject
        val module = root[moduleKey]?.jsonObject ?: return permissions
        val value = module[actionKey] ?: return permissions
        val current = effective(module, actionKey, value)
        val flippedModule = JsonObject(module + (actionKey to JsonPrimitive(!current)))
        val flippedRoot = JsonObject(root + (moduleKey to flippedModule))
        return json.decodeFromJsonElement(MemberPermissions.serializer(), flippedRoot)
    }

    /** The preset [permissions] currently matches exactly, or null for a custom mix. */
    fun matchingPreset(permissions: MemberPermissions): PermissionPreset? =
        PermissionPreset.entries.find { it.permissions() == permissions }

    /**
     * Every action granted, including settings — the app-layer representation of the
     * owner's implicit full access (§3; owners bypass the stored permissions object).
     */
    fun fullAccess(): MemberPermissions =
        MemberPermissions(
            booking =
                BookingPermissions(
                    view = true,
                    viewAmounts = true,
                    create = true,
                    edit = true,
                    delete = true,
                    recordPayment = true,
                    generateInvoice = true,
                ),
            expenses =
                ExpensesPermissions(view = true, viewAmounts = true, create = true, edit = true, delete = true, manageParties = true),
            inventory =
                InventoryPermissions(view = true, viewAmounts = true, create = true, edit = true, delete = true, manageMasterItems = true),
            notes = NotesPermissions(view = true, viewChecklists = true, create = true, edit = true, toggleChecklist = true, delete = true),
            reports = ReportsPermissions(view = true, viewAmounts = true),
            settings = SettingsPermissions(manageBusiness = true, manageMembers = true, gcalSync = true),
        )
}
