package com.itsluminous.samaroh.feature.inventory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.PermissionGate
import com.itsluminous.samaroh.core.designsystem.component.SamarohFab
import com.itsluminous.samaroh.core.designsystem.theme.animatedListItem
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.MasterItem
import com.itsluminous.samaroh.feature.inventory.MasterlistViewModel
import com.itsluminous.samaroh.feature.inventory.image.rememberItemImageModel

/**
 * Masterlist screen (§4.3): master-item CRUD with photo (square-cropped, ≤320px WebP),
 * unit dropdown, fuzzy duplicate chips while typing, and the delete-blocked-if-
 * transactions rule. The top-bar icon toggles back to the stock screen. CRUD
 * affordances are gated on `inventory.manage_master_items`/`inventory.edit`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterlistScreen(
    onOpenStock: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MasterlistViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val editor by viewModel.editor.collectAsState()
    val deleteRequest by viewModel.deleteRequest.collectAsState()
    val canManage by viewModel.canManageMasterItems.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inventory_masterlist_title), modifier = Modifier.semantics { heading() }) },
                actions = {
                    ExplainableIcon(
                        icon = Icons.Filled.Inventory2,
                        explanationRes = R.string.inventory_toggle_stock,
                        onClick = onOpenStock,
                    )
                },
            )
        },
        floatingActionButton = {
            if (canManage) {
                SamarohFab(onClick = { viewModel.openEditor() }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.inventory_masterlist_add_title),
                    )
                }
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Inventory2,
                title = stringResource(R.string.inventory_masterlist_empty_title),
                message = stringResource(R.string.inventory_masterlist_empty_message),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    MasterItemRow(
                        item = item,
                        canManage = canManage,
                        onEdit = { viewModel.openEditor(item) },
                        onDelete = { viewModel.requestDelete(item) },
                        modifier = animatedListItem(),
                    )
                }
            }
        }
    }

    editor?.let { state ->
        MasterItemEditorDialog(state = state, viewModel = viewModel)
    }

    deleteRequest?.let { request ->
        MasterItemDeleteDialogs(request = request, viewModel = viewModel)
    }
}

@Composable
private fun MasterItemRow(
    item: MasterItem,
    canManage: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val imageModel = rememberItemImageModel(item.imagePath)
            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = item.name,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = unitDisplayLabel(item.unit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PermissionGate(allowed = canManage) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ExplainableIcon(
                        icon = Icons.Filled.Edit,
                        explanationRes = R.string.common_action_edit,
                        onClick = onEdit,
                    )
                    ExplainableIcon(
                        icon = Icons.Filled.Delete,
                        explanationRes = R.string.common_action_delete,
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}
