package com.itsluminous.samaroh.core.auth.permissions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.component.ChipRow
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.MemberPermissions

/**
 * Reusable permission matrix editor (§3 owner grant UX): rows = capabilities grouped by
 * tab, a toggle per action, and Viewer/Staff/Manager quick-preset chips. Stateless —
 * exported from `core:auth` so the Menu tab's Members screen (W1-F) reuses it unchanged.
 *
 * Touch targets are ≥48dp (§6); every label comes from the string catalog.
 */
@Composable
fun PermissionMatrixEditor(
    permissions: MemberPermissions,
    onPermissionsChange: (MemberPermissions) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matchingPreset = PermissionMatrix.matchingPreset(permissions)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.auth_permissions_presets_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChipRow(modifier = Modifier.padding(top = 4.dp)) {
            PermissionPreset.entries.forEach { preset ->
                FilterChip(
                    selected = preset == matchingPreset,
                    onClick = { onPermissionsChange(preset.permissions()) },
                    label = { Text(presetLabel(preset)) },
                )
            }
        }
        PermissionMatrix.groups(permissions).forEach { group ->
            Text(
                text = moduleLabel(group.moduleKey),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            group.toggles.forEach { toggle ->
                Row(
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = actionLabel(toggle.actionKey),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = toggle.enabled,
                        onCheckedChange = {
                            onPermissionsChange(PermissionMatrix.toggle(permissions, group.moduleKey, toggle.actionKey))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun presetLabel(preset: PermissionPreset): String =
    stringResource(
        when (preset) {
            PermissionPreset.VIEWER -> R.string.auth_permissions_preset_viewer
            PermissionPreset.STAFF -> R.string.auth_permissions_preset_staff
            PermissionPreset.MANAGER -> R.string.auth_permissions_preset_manager
        },
    )

@Composable
private fun moduleLabel(moduleKey: String): String =
    when (moduleKey) {
        "booking" -> stringResource(R.string.auth_permissions_group_booking)
        "expenses" -> stringResource(R.string.auth_permissions_group_expenses)
        "inventory" -> stringResource(R.string.auth_permissions_group_inventory)
        "reports" -> stringResource(R.string.auth_permissions_group_reports)
        "settings" -> stringResource(R.string.auth_permissions_group_settings)
        // Unknown future schema module: show the raw key rather than crash.
        else -> moduleKey
    }

@Composable
private fun actionLabel(actionKey: String): String =
    when (actionKey) {
        "view" -> stringResource(R.string.auth_permissions_action_view)
        "create" -> stringResource(R.string.auth_permissions_action_create)
        "edit" -> stringResource(R.string.auth_permissions_action_edit)
        "delete" -> stringResource(R.string.auth_permissions_action_delete)
        "record_payment" -> stringResource(R.string.auth_permissions_action_record_payment)
        "generate_invoice" -> stringResource(R.string.auth_permissions_action_generate_invoice)
        "manage_parties" -> stringResource(R.string.auth_permissions_action_manage_parties)
        "manage_master_items" -> stringResource(R.string.auth_permissions_action_manage_master_items)
        "manage_business" -> stringResource(R.string.auth_permissions_action_manage_business)
        "manage_members" -> stringResource(R.string.auth_permissions_action_manage_members)
        "gcal_sync" -> stringResource(R.string.auth_permissions_action_gcal_sync)
        // Unknown future schema action: show the raw key rather than crash.
        else -> actionKey
    }
