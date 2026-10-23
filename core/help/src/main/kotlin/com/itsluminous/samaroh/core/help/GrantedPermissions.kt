package com.itsluminous.samaroh.core.help

import com.itsluminous.samaroh.core.model.MemberPermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Answers "does this member hold `<section>.<capability>`?" for the guide's `permission`
 * gates (ADR-108, hide-not-grey: a task the member cannot perform is not listed).
 *
 * The capability names are the wire names of `permissions/permissions-schema.json`, which
 * are exactly the `@SerialName`s of [MemberPermissions] — so the lookup serializes the
 * permissions once and reads the JSON tree, with the two inherited capabilities
 * (`files.manage_folders` ← `upload`, `notes.view_checklists` ← `view`,
 * `notes.toggle_checklist` ← `edit`) resolved to their EFFECTIVE value (design D5/D7).
 * Owners hold everything. An unknown key is NOT granted (fails closed).
 */
class GrantedPermissions private constructor(
    private val isOwner: Boolean,
    private val tree: JsonObject,
) {
    /** Whether [permissionKey] (`booking.record_payment`) is held; null = no gate = granted. */
    fun holds(permissionKey: String?): Boolean {
        if (permissionKey == null || isOwner) return true
        val (section, capability) = permissionKey.split('.', limit = 2).takeIf { it.size == 2 } ?: return false
        val sectionTree = tree[section] as? JsonObject ?: return false
        val raw = sectionTree[capability]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        return raw ?: inherited(sectionTree, section, capability) ?: false
    }

    private fun inherited(
        sectionTree: JsonObject,
        section: String,
        capability: String,
    ): Boolean? {
        val parent =
            when (section to capability) {
                "files" to "manage_folders" -> "upload"
                "notes" to "view_checklists" -> "view"
                "notes" to "toggle_checklist" -> "edit"
                else -> return null
            }
        return sectionTree[parent]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
    }

    companion object {
        private val json = Json { encodeDefaults = true }

        /** Everything granted: owners, and the signed-out / offline owner mode (no membership row). */
        val OWNER: GrantedPermissions = GrantedPermissions(isOwner = true, tree = JsonObject(emptyMap()))

        fun of(
            isOwner: Boolean,
            permissions: MemberPermissions,
        ): GrantedPermissions =
            if (isOwner) {
                OWNER
            } else {
                GrantedPermissions(isOwner = false, tree = json.encodeToJsonElement(MemberPermissions.serializer(), permissions).jsonObject)
            }
    }
}
