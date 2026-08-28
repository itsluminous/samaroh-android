package com.itsluminous.samaroh.feature.inventory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.itsluminous.samaroh.core.designsystem.component.AmountText
import com.itsluminous.samaroh.core.designsystem.component.ChipRow
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.theme.animatedListItem
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.TxnType
import com.itsluminous.samaroh.feature.inventory.ItemDetailViewModel
import com.itsluminous.samaroh.feature.inventory.MasterlistViewModel
import com.itsluminous.samaroh.feature.inventory.SavedTransaction
import com.itsluminous.samaroh.feature.inventory.domain.formatQuantity
import com.itsluminous.samaroh.feature.inventory.image.rememberItemImageModel
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * Per-item detail screen (§4.3 parity): header with photo, name, stock and FIFO total
 * value; Add/Remove buttons that open the transaction dialog pre-selected to this item;
 * and the newest-first transaction history, windowed 20 at a time with Load more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ItemDetailViewModel = hiltViewModel(),
    masterlistViewModel: MasterlistViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val editor by masterlistViewModel.editor.collectAsState()
    val deleteRequest by masterlistViewModel.deleteRequest.collectAsState()
    val canManage by masterlistViewModel.canManageMasterItems.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var dialogType by remember { mutableStateOf<TxnType?>(null) }
    var expandedImagePath by remember { mutableStateOf<String?>(null) }
    var overflowMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.item?.name.orEmpty(),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        explanationRes = R.string.inventory_detail_back,
                        onClick = onBack,
                    )
                },
                actions = {
                    // Edit/delete affordance (party-ledger parity): overflow menu opening
                    // the SAME editor dialog + delete flow as the Masterlist screen.
                    val item = uiState.item
                    if (item != null && canManage) {
                        Box {
                            ExplainableIcon(
                                icon = Icons.Filled.MoreVert,
                                explanationRes = R.string.inventory_detail_more_options,
                                onClick = { overflowMenu = true },
                            )
                            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.inventory_detail_edit_item)) },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    onClick = {
                                        overflowMenu = false
                                        masterlistViewModel.openEditor(item)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.inventory_detail_delete_item)) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = {
                                        overflowMenu = false
                                        masterlistViewModel.requestDelete(item)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val item = uiState.item
        if (!uiState.loading && item == null) {
            // Item deleted (or never existed): nothing to show here.
            EmptyState(
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                title = stringResource(R.string.inventory_detail_empty),
                message = "",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                if (item != null) {
                    val canRecord by viewModel.canRecordTransactions.collectAsState()
                    ItemDetailHeader(
                        name = item.name,
                        unit = item.unit,
                        imagePath = item.imagePath,
                        currentQuantity = uiState.currentQuantity,
                        totalValuePaise = uiState.totalValuePaise,
                        showTransactionButtons = canRecord,
                        onImageTap = { path -> expandedImagePath = path },
                        onAdd = { dialogType = TxnType.ADD },
                        onRemove = { dialogType = TxnType.REMOVE },
                    )
                }
            }
            item(key = "history-title") {
                Text(
                    text = stringResource(R.string.inventory_detail_transactions_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
            }
            if (!uiState.loading && uiState.totalTransactionCount == 0) {
                item(key = "history-empty") {
                    Text(
                        text = stringResource(R.string.inventory_detail_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(uiState.transactions, key = { it.id }) { txn ->
                TransactionRowCard(txn = txn, unit = item?.unit.orEmpty(), modifier = animatedListItem())
            }
            item(key = "footer") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    if (uiState.totalTransactionCount > 0) {
                        Text(
                            text =
                                stringResource(
                                    R.string.inventory_detail_showing_count,
                                    uiState.transactions.size.toString(),
                                    uiState.totalTransactionCount.toString(),
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (uiState.hasMore) {
                        TextButton(onClick = viewModel::loadMore) {
                            Text(stringResource(R.string.inventory_detail_load_more))
                        }
                    }
                }
            }
        }
    }

    dialogType?.let { type ->
        RecordTransactionDialog(
            onDismiss = { dialogType = null },
            preselectedItemId = viewModel.itemId,
            initialType = type,
            onSaved = { saved ->
                scope.launch { snackbarHostState.showSnackbar(savedTransactionMessage(context, saved)) }
            },
        )
    }

    editor?.let { state ->
        MasterItemEditorDialog(state = state, viewModel = masterlistViewModel)
    }

    deleteRequest?.let { request ->
        // Same delete flow as the Masterlist (delete-blocked-if-transactions rule);
        // a confirmed delete leaves the now-gone item's screen.
        MasterItemDeleteDialogs(request = request, viewModel = masterlistViewModel, onDeleted = onBack)
    }

    expandedImagePath?.let { path ->
        Dialog(onDismissRequest = { expandedImagePath = null }) {
            AsyncImage(
                model = rememberItemImageModel(path),
                contentDescription = stringResource(R.string.inventory_image_expanded),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { expandedImagePath = null },
            )
        }
    }
}

/** Localized snackbar text for a recorded transaction (remove includes the FIFO cost). */
fun savedTransactionMessage(
    context: android.content.Context,
    saved: SavedTransaction,
): String =
    when (saved.type) {
        TxnType.ADD -> context.getString(R.string.inventory_txn_saved_add)
        TxnType.REMOVE ->
            context.getString(
                R.string.inventory_txn_saved_remove,
                AmountFormatter.format(saved.totalValuePaise),
            )
    }

@Composable
private fun ItemDetailHeader(
    name: String,
    unit: String,
    imagePath: String?,
    currentQuantity: Double,
    totalValuePaise: Long,
    showTransactionButtons: Boolean,
    onImageTap: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val imageModel = rememberItemImageModel(imagePath)
                if (imageModel != null && imagePath != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = name,
                        modifier =
                            Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onImageTap(imagePath) },
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text =
                            stringResource(
                                R.string.inventory_list_quantity_with_unit,
                                formatQuantity(currentQuantity),
                                unitDisplayLabel(unit),
                            ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.inventory_detail_total_value),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AmountText(amountPaise = totalValuePaise, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            // Scrollable single line: weighted halves squash "Add"/"Remove" into
            // character-per-line slivers on narrow screens (pills never wrap).
            // §3 gate: hidden entirely without inventory.create.
            if (showTransactionButtons) {
                ChipRow {
                    Button(onClick = onAdd) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                        Text(stringResource(R.string.inventory_txn_type_add))
                    }
                    OutlinedButton(onClick = onRemove) {
                        Icon(imageVector = Icons.Filled.Remove, contentDescription = null)
                        Text(stringResource(R.string.inventory_txn_type_remove))
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionRowCard(
    txn: InventoryTransaction,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TxnTypeChip(type = txn.transactionType)
                Text(
                    text = formatDateTime(txn.transactionDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                AmountText(
                    amountPaise = (txn.quantity * txn.unitPricePaise).roundToLong(),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text =
                    stringResource(
                        R.string.inventory_detail_qty_at_price,
                        formatQuantity(txn.quantity),
                        unitDisplayLabel(unit),
                        AmountFormatter.format(txn.unitPricePaise),
                    ),
                style = MaterialTheme.typography.bodyLarge,
            )
            txn.notes?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Colored ADD/REMOVE marker on a history row. */
@Composable
private fun TxnTypeChip(type: TxnType) {
    val (container, content, labelRes) =
        when (type) {
            TxnType.ADD ->
                Triple(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    R.string.inventory_txn_type_add,
                )
            TxnType.REMOVE ->
                Triple(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                    R.string.inventory_txn_type_remove,
                )
        }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
        }
    }
}
