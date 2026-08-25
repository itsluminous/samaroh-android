package com.itsluminous.samaroh.feature.expenses.ledger

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
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
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.ExpenseDirection
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
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.party?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
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
