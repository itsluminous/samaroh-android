package com.itsluminous.samaroh.feature.inventory.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.itsluminous.samaroh.core.designsystem.component.cropper.SquareImageCropperDialog
import com.itsluminous.samaroh.core.designsystem.component.cropper.loadCropSourceBitmap
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.inventory.DeleteRequestState
import com.itsluminous.samaroh.feature.inventory.MasterItemEditorState
import com.itsluminous.samaroh.feature.inventory.MasterItemFormError
import com.itsluminous.samaroh.feature.inventory.MasterlistViewModel
import com.itsluminous.samaroh.feature.inventory.UnitOption
import com.itsluminous.samaroh.feature.inventory.image.rememberItemImageModel
import kotlinx.coroutines.launch

/*
 * The master-item add/edit dialog and the delete confirm/blocked dialogs (§4.3), shared
 * verbatim between the Masterlist screen and the per-item detail screen so both entry
 * points get the SAME validation (dup chips, unit dropdown, photo crop) and the same
 * delete-blocked-if-transactions rule.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MasterItemEditorDialog(
    state: MasterItemEditorState,
    viewModel: MasterlistViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Picker result → decoded source bitmap → interactive square cropper → ViewModel.
    var cropSource by remember { mutableStateOf<Bitmap?>(null) }
    val pickImage =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) scope.launch { cropSource = loadCropSourceBitmap(context, uri) }
        }

    cropSource?.let { source ->
        SquareImageCropperDialog(
            bitmap = source,
            onConfirm = { cropped ->
                viewModel.onImageCropped(cropped)
                cropSource = null
            },
            onDismiss = { cropSource = null },
        )
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
            // Scrolls when the IME shrinks the dialog; the action buttons live outside.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val editorImageModel = rememberItemImageModel(state.imagePath)
                    if (editorImageModel != null) {
                        AsyncImage(
                            model = editorImageModel,
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
                        items(state.duplicates, key = { it.item.id }) { duplicate ->
                            AssistChip(
                                onClick = { viewModel.onDuplicateSelected(duplicate.item) },
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.inventory_masterlist_similar_chip,
                                            duplicate.item.name,
                                            duplicate.percent.toString(),
                                        ),
                                    )
                                },
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

/**
 * Delete flow dialogs: confirmation when deletable, the delete-blocked-if-transactions
 * notice otherwise. [onDeleted] fires right after a confirmed delete (the detail screen
 * navigates back; the masterlist stays put).
 */
@Composable
internal fun MasterItemDeleteDialogs(
    request: DeleteRequestState,
    viewModel: MasterlistViewModel,
    onDeleted: () -> Unit = {},
) {
    if (request.deletable) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.inventory_masterlist_delete_confirm_title)) },
            text = { Text(stringResource(R.string.inventory_masterlist_delete_confirm_message, request.item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmDelete()
                    onDeleted()
                }) {
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
