package com.itsluminous.samaroh.feature.inventory.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import coil.compose.AsyncImage
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.MasterItem
import com.itsluminous.samaroh.feature.inventory.MasterItemEditorState
import com.itsluminous.samaroh.feature.inventory.MasterItemFormError
import com.itsluminous.samaroh.feature.inventory.MasterlistViewModel
import com.itsluminous.samaroh.feature.inventory.UnitOption
import java.io.File

/**
 * Masterlist screen (§4.3): master-item CRUD with photo (square-cropped, ≤320px WebP),
 * unit dropdown, fuzzy duplicate chips while typing, and the delete-blocked-if-
 * transactions rule. The top-bar icon toggles back to the stock screen.
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inventory_masterlist_title)) },
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
            FloatingActionButton(onClick = { viewModel.openEditor() }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.inventory_masterlist_add_title),
                )
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
                        onEdit = { viewModel.openEditor(item) },
                        onDelete = { viewModel.requestDelete(item) },
                    )
                }
            }
        }
    }

    editor?.let { state ->
        MasterItemEditorDialog(state = state, viewModel = viewModel)
    }

    deleteRequest?.let { request ->
        if (request.deletable) {
            AlertDialog(
                onDismissRequest = viewModel::dismissDelete,
                title = { Text(stringResource(R.string.inventory_masterlist_delete_confirm_title)) },
                text = { Text(stringResource(R.string.inventory_masterlist_delete_confirm_message, request.item.name)) },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmDelete) {
                        Text(stringResource(R.string.common_action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissDelete) {
                        Text(stringResource(R.string.common_action_cancel))
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = viewModel::dismissDelete,
                title = { Text(stringResource(R.string.inventory_masterlist_delete_confirm_title)) },
                text = { Text(stringResource(R.string.inventory_masterlist_delete_blocked)) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissDelete) {
                        Text(stringResource(R.string.common_action_close))
                    }
                },
            )
        }
    }
}

@Composable
private fun MasterItemRow(
    item: MasterItem,
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
            if (item.imagePath != null) {
                AsyncImage(
                    model = File(item.imagePath ?: ""),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MasterItemEditorDialog(
    state: MasterItemEditorState,
    viewModel: MasterlistViewModel,
) {
    val pickImage =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) viewModel.onImagePicked(uri)
        }

    AlertDialog(
        onDismissRequest = viewModel::dismissEditor,
        title = {
            Text(
                stringResource(
                    if (state.editingItem == null) R.string.inventory_masterlist_add_title else R.string.inventory_masterlist_edit_title,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { viewModel.saveItem() }, enabled = !state.saving) {
                Text(stringResource(R.string.common_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissEditor) {
                Text(stringResource(R.string.common_action_cancel))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.imagePath != null) {
                        AsyncImage(
                            model = File(state.imagePath ?: ""),
                            contentDescription = stringResource(R.string.inventory_image_expanded),
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PhotoCamera,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column {
                        TextButton(
                            onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        ) {
                            Text(stringResource(R.string.inventory_masterlist_photo_pick))
                        }
                        if (state.imagePath != null) {
                            TextButton(onClick = viewModel::onImageRemoved) {
                                Text(stringResource(R.string.inventory_masterlist_photo_remove))
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text(stringResource(R.string.inventory_masterlist_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.duplicates.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.inventory_masterlist_similar_items_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.duplicates, key = { it.id }) { duplicate ->
                            AssistChip(
                                onClick = { viewModel.onDuplicateSelected(duplicate) },
                                label = { Text(duplicate.name) },
                            )
                        }
                    }
                }
                UnitDropdown(state = state, viewModel = viewModel)
                if (state.unitOption == UnitOption.CUSTOM) {
                    OutlinedTextField(
                        value = state.customUnit,
                        onValueChange = viewModel::onCustomUnitChange,
                        label = { Text(stringResource(R.string.inventory_masterlist_custom_unit_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.error?.let { error ->
                    Text(
                        text = editorErrorMessage(error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitDropdown(
    state: MasterItemEditorState,
    viewModel: MasterlistViewModel,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = unitOptionLabel(state.unitOption),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.inventory_masterlist_unit_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            UnitOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(unitOptionLabel(option)) },
                    onClick = {
                        viewModel.onUnitOptionChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun editorErrorMessage(error: MasterItemFormError): String =
    when (error) {
        MasterItemFormError.NAME_REQUIRED -> stringResource(R.string.inventory_masterlist_error_name_required)
        MasterItemFormError.UNIT_REQUIRED -> stringResource(R.string.inventory_masterlist_error_unit_required)
        MasterItemFormError.DUPLICATE_NAME -> stringResource(R.string.inventory_masterlist_error_duplicate_name)
    }
