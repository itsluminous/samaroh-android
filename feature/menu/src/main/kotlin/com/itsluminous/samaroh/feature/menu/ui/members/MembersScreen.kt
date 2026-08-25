package com.itsluminous.samaroh.feature.menu.ui.members

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.MemberStatus
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold

/**
 * Members screen (§4.4, OWNER ONLY): status chips, add by email + display name,
 * permission editor slot per member.
 */
@Composable
fun MembersScreen(
    onBack: () -> Unit,
    viewModel: MembersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var expandedMemberId by rememberSaveable { mutableStateOf<String?>(null) }

    if (!state.isOwner) {
        // §3: employees never see member management.
        MenuScreenScaffold(titleRes = R.string.menu_members_title, onBack = onBack) {
            if (!state.loading) {
                Text(
                    text = stringResource(R.string.menu_members_owner_only),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        return
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Filled.Group, contentDescription = null) },
                text = { Text(stringResource(R.string.menu_members_add)) },
            )
        },
    ) { padding ->
        MenuScreenScaffold(
            titleRes = R.string.menu_members_title,
            onBack = onBack,
            modifier = Modifier.padding(padding),
        ) {
            if (state.members.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Group,
                    title = stringResource(R.string.menu_members_empty_title),
                    message = stringResource(R.string.menu_members_empty_message),
                )
            } else {
                for (member in state.members) {
                    MemberRow(
                        member = member,
                        expanded = expandedMemberId == member.id,
                        onToggleExpand = {
                            expandedMemberId = if (expandedMemberId == member.id) null else member.id
                        },
                        onRevoke = { viewModel.revokeMember(member) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        AddMemberDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { email, name ->
                viewModel.addMember(email, name)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun MemberRow(
    member: BusinessMember,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRevoke: () -> Unit,
) {
    Column {
        ListItem(
            headlineContent = { Text(member.displayName, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(member.invitedEmail) },
            trailingContent = {
                if (member.isOwner) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.menu_members_owner_badge)) })
                } else {
                    AssistChip(onClick = onToggleExpand, label = { Text(statusLabel(member.status)) })
                }
            },
            modifier = Modifier.clickable(onClick = onToggleExpand),
        )
        if (expanded && !member.isOwner) {
            Text(
                text = stringResource(R.string.menu_members_permissions_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            PermissionMatrixEditorSlot(member = member)
            TextButton(
                onClick = onRevoke,
                enabled = member.status != MemberStatus.REVOKED,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.menu_members_revoke), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * ═══ INTEGRATOR SLOT ═══
 * Placeholder for `core:auth`'s `PermissionMatrixEditor` (W1-D deliverable, §3 permission
 * matrix). Not on this branch yet — swap this composable for the real editor at wave
 * merge, keeping the [member] parameter as the editing target.
 */
@Composable
fun PermissionMatrixEditorSlot(
    member: BusinessMember,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.menu_members_permissions_placeholder),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(16.dp),
    )
}

@Composable
private fun AddMemberDialog(
    onDismiss: () -> Unit,
    onAdd: (email: String, name: String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_members_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.menu_members_email_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.menu_members_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(email, name) },
                enabled = email.contains('@') && name.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_action_cancel))
            }
        },
    )
}

@Composable
private fun statusLabel(status: MemberStatus): String =
    when (status) {
        MemberStatus.INVITED -> stringResource(R.string.menu_members_status_invited)
        MemberStatus.ACTIVE -> stringResource(R.string.menu_members_status_active)
        MemberStatus.REVOKED -> stringResource(R.string.menu_members_status_revoked)
    }
