package com.itsluminous.samaroh.feature.expenses.ledger

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.designsystem.component.AmountText
import com.itsluminous.samaroh.core.designsystem.component.AmountTone
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.PermissionGate
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.designsystem.theme.animatedListItem
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.feature.expenses.BusinessRelatedPill
import com.itsluminous.samaroh.feature.expenses.PersonalPartyTag
import com.itsluminous.samaroh.feature.expenses.domain.LedgerRow
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Person ledger (§4.2): newest-first entries, balance-after chips, big gave/got buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyLedgerScreen(
    onBack: () -> Unit,
    onAddEntry: (direction: ExpenseDirection) -> Unit,
    onEditEntry: (direction: ExpenseDirection, expenseId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PartyLedgerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editPartyError by viewModel.editPartyError.collectAsStateWithLifecycle()
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var showEditParty by remember { mutableStateOf(false) }
    var confirmDeleteParty by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val deletedNoticeTemplate = stringResource(R.string.expenses_party_deleted_notice)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PartyLedgerEvent.PartySaved -> showEditParty = false
                is PartyLedgerEvent.PartyDeleted -> {
                    Toast.makeText(context, deletedNoticeTemplate.format(event.partyName), Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.party?.name.orEmpty(),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                            if (state.party?.businessRelated == false) {
                                PersonalPartyTag(modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        state.party?.phone?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.expenses_a11y_back),
                        )
                    }
                },
                actions = {
                    if (state.party != null && (state.canEditParty || state.canDeleteParty)) {
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            ExplainableIcon(
                                icon = Icons.Filled.MoreVert,
                                explanationRes = R.string.expenses_party_menu,
                                onClick = { menuOpen = true },
                            )
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (state.canEditParty) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.expenses_party_edit_title)) },
                                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            showEditParty = true
                                        },
                                    )
                                }
                                if (state.canDeleteParty) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.expenses_party_delete_action)) },
                                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            confirmDeleteParty = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { onAddEntry(ExpenseDirection.PAID) },
                    colors = ButtonDefaults.buttonColors(containerColor = SamarohTheme.semanticColors.moneyOut),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text(stringResource(R.string.expenses_ledger_you_gave_button), style = MaterialTheme.typography.titleMedium)
                }
                Button(
                    onClick = { onAddEntry(ExpenseDirection.RECEIVED) },
                    colors = ButtonDefaults.buttonColors(containerColor = SamarohTheme.semanticColors.moneyIn),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text(stringResource(R.string.expenses_ledger_you_got_button), style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            NetBalanceHeader(netBalancePaise = state.netBalancePaise)
            if (state.loaded && state.rows.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.ReceiptLong,
                    title = stringResource(R.string.expenses_ledger_empty_title),
                    message = stringResource(R.string.expenses_ledger_empty_message),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.expense.id }) { row ->
                        LedgerEntryRow(
                            row = row,
                            attachments = state.attachmentsByExpense[row.expense.id].orEmpty(),
                            canEdit = state.canEditEntries,
                            onEdit = { onEditEntry(row.expense.direction, row.expense.id) },
                            onDelete = { confirmDeleteId = row.expense.id },
                            modifier = animatedListItem(),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    confirmDeleteId?.let { pendingId ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text(stringResource(R.string.expenses_ledger_delete_title)) },
            text = { Text(stringResource(R.string.expenses_ledger_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(pendingId)
                    confirmDeleteId = null
                }) { Text(stringResource(R.string.common_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }

    if (showEditParty) {
        state.party?.let { party ->
            EditPartyDialog(
                initialName = party.name,
                initialPhone = party.phone.orEmpty(),
                initialBusinessRelated = party.businessRelated,
                businessName = state.businessName,
                error = editPartyError,
                onErrorConsumed = viewModel::clearEditPartyError,
                onDismiss = {
                    viewModel.clearEditPartyError()
                    showEditParty = false
                },
                onSave = { name, phone, businessRelated -> viewModel.saveParty(name, phone, businessRelated) },
            )
        }
    }

    if (confirmDeleteParty) {
        state.party?.let { party ->
            AlertDialog(
                onDismissRequest = { confirmDeleteParty = false },
                title = { Text(stringResource(R.string.expenses_party_delete_confirm_title, party.name)) },
                text = { Text(stringResource(R.string.expenses_party_delete_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDeleteParty = false
                        viewModel.deleteParty()
                    }) { Text(stringResource(R.string.common_action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteParty = false }) { Text(stringResource(R.string.common_action_cancel)) }
                },
            )
        }
    }
}

/**
 * Edit-party dialog (ADR-028): full parity with the add-person form — name (deduped
 * against the business's other parties), optional phone, business/personal pill.
 */
@Composable
private fun EditPartyDialog(
    initialName: String,
    initialPhone: String,
    initialBusinessRelated: Boolean,
    businessName: String,
    error: EditPartyError?,
    onErrorConsumed: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (name: String, phone: String, businessRelated: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var phone by remember { mutableStateOf(initialPhone) }
    var businessRelated by remember { mutableStateOf(initialBusinessRelated) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expenses_party_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        onErrorConsumed()
                    },
                    label = { Text(stringResource(R.string.expenses_add_person_name_label)) },
                    isError = error != null,
                    supportingText = {
                        when (error) {
                            EditPartyError.EMPTY_NAME -> Text(stringResource(R.string.expenses_add_person_name_required))
                            EditPartyError.DUPLICATE_NAME -> Text(stringResource(R.string.expenses_person_duplicate_exists))
                            null -> Unit
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(stringResource(R.string.expenses_add_person_phone_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                BusinessRelatedPill(
                    businessName = businessName,
                    businessRelated = businessRelated,
                    onBusinessRelatedChange = { businessRelated = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, phone, businessRelated) }) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

@Composable
private fun NetBalanceHeader(
    netBalancePaise: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.expenses_ledger_net_balance_label), style = MaterialTheme.typography.bodyLarge)
        AmountText(
            amountPaise = if (netBalancePaise < 0) -netBalancePaise else netBalancePaise,
            tone = if (netBalancePaise >= 0) AmountTone.MONEY_OUT else AmountTone.MONEY_IN,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun LedgerEntryRow(
    row: LedgerRow,
    attachments: List<AttachmentWithLocalState>,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expense = row.expense
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = expense.expenseDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            expense.notes?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
            }
            if (attachments.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    attachments.forEach { AttachmentThumbnail(it) }
                }
            }
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        stringResource(
                            R.string.expenses_ledger_balance_after,
                            AmountFormatter.format(if (row.balanceAfterPaise < 0) -row.balanceAfterPaise else row.balanceAfterPaise),
                        ),
                    )
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            AmountText(
                amountPaise = expense.amountPaise,
                tone = if (expense.direction == ExpenseDirection.PAID) AmountTone.MONEY_OUT else AmountTone.MONEY_IN,
                style = MaterialTheme.typography.titleMedium,
            )
            PermissionGate(allowed = canEdit) {
                EntryMenu(onEdit = onEdit, onDelete = onDelete)
            }
        }
    }
}

@Composable
private fun EntryMenu(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ExplainableIcon(
            icon = Icons.Filled.MoreVert,
            explanationRes = R.string.expenses_ledger_entry_menu,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_action_edit)) },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_action_delete)) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    attachment: AttachmentWithLocalState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(56.dp)) {
        val localFile = attachment.localCachePath?.let(::File)?.takeIf { it.exists() }
        if (attachment.attachment.mimeType.startsWith("image/") && localFile != null) {
            AsyncImage(
                model = localFile,
                contentDescription = stringResource(R.string.expenses_ledger_attachment_thumbnail),
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = stringResource(R.string.expenses_ledger_attachment_thumbnail),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        if (attachment.isPendingUpload) {
            // Visible pending badge (§4.2): the file has not reached Google Drive yet.
            ExplainableIcon(
                icon = Icons.Filled.CloudUpload,
                explanationRes = R.string.expenses_ledger_pending_upload,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
            )
        }
    }
}
