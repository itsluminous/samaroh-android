package com.itsluminous.samaroh.feature.inventory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine
import com.itsluminous.samaroh.core.designsystem.component.AmountText
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.SamarohFab
import com.itsluminous.samaroh.core.designsystem.theme.animatedListItem
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.inventory.CurrentInventoryViewModel
import com.itsluminous.samaroh.feature.inventory.domain.formatQuantity
import kotlinx.coroutines.launch
import java.io.File

/**
 * Current Inventory screen (§4.3): searchable per-item stock list (in-stock items only)
 * with image (tap-to-expand), quantity + unit, FIFO total value and last-updated date.
 * Tapping a row opens the per-item detail (transaction history). The top-bar icon
 * toggles to the item (master) list; the FAB opens the record-transaction dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrentInventoryScreen(
    onOpenMasterlist: () -> Unit,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CurrentInventoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showTransactionDialog by remember { mutableStateOf(false) }
    var expandedImagePath by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inventory_list_title), modifier = Modifier.semantics { heading() }) },
                actions = {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.ListAlt,
                        explanationRes = R.string.inventory_toggle_masterlist,
                        onClick = onOpenMasterlist,
                    )
                },
            )
        },
        floatingActionButton = {
            SamarohFab(onClick = { showTransactionDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.inventory_fab_record_transaction),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                label = { Text(stringResource(R.string.inventory_list_search_placeholder)) },
                leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                !uiState.loading && uiState.lines.isEmpty() && uiState.noSearchResults ->
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.inventory_list_no_results),
                        message = stringResource(R.string.inventory_list_empty_message),
                    )
                // Items exist but every one is at zero: distinct from the no-items state.
                !uiState.loading && uiState.lines.isEmpty() && uiState.allZero ->
                    EmptyState(
                        icon = Icons.Filled.Inventory2,
                        title = stringResource(R.string.inventory_list_all_zero_title),
                        message = stringResource(R.string.inventory_list_all_zero_message),
                    )
                !uiState.loading && uiState.lines.isEmpty() ->
                    EmptyState(
                        icon = Icons.Filled.Inventory2,
                        title = stringResource(R.string.inventory_list_empty_title),
                        message = stringResource(R.string.inventory_list_empty_message),
                    )
                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.lines, key = { it.masterItemId }) { line ->
                            CurrentInventoryRowCard(
                                line = line,
                                onClick = { onOpenItem(line.masterItemId) },
                                onImageTap = { path -> expandedImagePath = path },
                                modifier = animatedListItem(),
                            )
                        }
                    }
            }
        }
    }

    if (showTransactionDialog) {
        RecordTransactionDialog(
            onDismiss = { showTransactionDialog = false },
            onSaved = { saved ->
                scope.launch { snackbarHostState.showSnackbar(savedTransactionMessage(context, saved)) }
            },
        )
    }

    expandedImagePath?.let { path ->
        Dialog(onDismissRequest = { expandedImagePath = null }) {
            AsyncImage(
                model = File(path),
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

@Composable
private fun CurrentInventoryRowCard(
    line: CurrentInventoryLine,
    onClick: () -> Unit,
    onImageTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (line.imagePath != null) {
                AsyncImage(
                    model = File(line.imagePath),
                    contentDescription = line.name,
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onImageTap(line.imagePath ?: return@clickable) },
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = line.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        stringResource(
                            R.string.inventory_list_quantity_with_unit,
                            formatQuantity(line.currentQuantity),
                            unitDisplayLabel(line.unit),
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                line.lastTransactionAt?.let {
                    Text(
                        text = stringResource(R.string.inventory_list_last_updated, formatDate(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AmountText(
                amountPaise = line.totalValuePaise,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
