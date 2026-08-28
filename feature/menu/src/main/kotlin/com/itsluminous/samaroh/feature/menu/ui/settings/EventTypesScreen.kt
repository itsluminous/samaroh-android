package com.itsluminous.samaroh.feature.menu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.ColorSwatchEntry
import com.itsluminous.samaroh.core.designsystem.component.ColorSwatchPicker
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.parseHexColor
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold

/**
 * Menu → Settings → Event types (ADR-032): manage the business's booking event-type
 * presets. Gated on owner / `settings.manage_business` — the Settings row hides without
 * it, and this screen renders nothing actionable as defence in depth. List rows show
 * icon + label + colour dot in sort order with up/down reorder arrows; tapping a row
 * edits it; delete is SOFT and the confirmation says old bookings keep their type.
 */
@Composable
fun EventTypesScreen(
    onBack: () -> Unit,
    viewModel: EventTypesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()

    MenuScreenScaffold(
        titleRes = R.string.settings_event_types_title,
        onBack = onBack,
        scrollable = false,
    ) { contentModifier ->
        Column(modifier = contentModifier.verticalScroll(rememberScrollState())) {
            if (!state.loading && !state.canManage) {
                // Defence in depth: reaching the route without permission shows nothing.
                return@Column
            }
            if (!state.loading && state.presets.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_event_types_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            state.presets.forEachIndexed { index, preset ->
                PresetRow(
                    preset = preset,
                    dotColor =
                        viewModel.bookingColorsProvider
                            .byKey(preset.color)
                            ?.hex
                            ?.let(::parseHexColor),
                    canMoveUp = index > 0,
                    canMoveDown = index < state.presets.lastIndex,
                    onEdit = { viewModel.startEdit(preset) },
                    onMoveUp = { viewModel.move(preset, up = true) },
                    onMoveDown = { viewModel.move(preset, up = false) },
                    onDelete = { viewModel.requestDelete(preset) },
                )
                HorizontalDivider()
            }
            // Add row (48dp+ target; the label carries the meaning).
            ListItem(
                leadingContent = {
                    ExplainableIcon(icon = Icons.Filled.Add, explanationRes = R.string.settings_event_types_add_title)
                },
                headlineContent = { Text(stringResource(R.string.settings_event_types_add_title)) },
                modifier = Modifier.clickable(onClick = viewModel::startAdd),
            )
        }
    }

    draft?.let { current ->
        EventTypeDialog(
            draft = current,
            viewModel = viewModel,
        )
    }

    pendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.settings_event_types_delete_title)) },
            text = { Text(stringResource(R.string.settings_event_types_delete_message, preset.label)) },
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
    }
}

@Composable
private fun PresetRow(
    preset: EventType,
    dotColor: androidx.compose.ui.graphics.Color?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        leadingContent = { Text(text = preset.icon, style = MaterialTheme.typography.titleLarge) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = preset.label, modifier = Modifier.weight(1f, fill = false))
                if (dotColor != null) {
                    // Decorative colour dot — the edit dialog announces the colour name.
                    Box(
                        modifier =
                            Modifier
                                .padding(start = 8.dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                    )
                }
            }
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canMoveUp) {
                    ExplainableIcon(
                        icon = Icons.Filled.KeyboardArrowUp,
                        explanationRes = R.string.settings_event_types_move_up,
                        onClick = onMoveUp,
                    )
                }
                if (canMoveDown) {
                    ExplainableIcon(
                        icon = Icons.Filled.KeyboardArrowDown,
                        explanationRes = R.string.settings_event_types_move_down,
                        onClick = onMoveDown,
                    )
                }
                ExplainableIcon(
                    icon = Icons.Filled.Delete,
                    explanationRes = R.string.settings_event_types_delete_title,
                    onClick = onDelete,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onEdit),
    )
}

/** Add/edit dialog: label (with duplicate validation), emoji field, colour swatches. */
@Composable
private fun EventTypeDialog(
    draft: EventTypeDraft,
    viewModel: EventTypesViewModel,
) {
    val titleRes = if (draft.id == null) R.string.settings_event_types_add_title else R.string.settings_event_types_edit_title
    AlertDialog(
        onDismissRequest = viewModel::dismissDraft,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = viewModel::setDraftLabel,
                    label = { Text(stringResource(R.string.settings_event_types_name_label)) },
                    isError = draft.duplicateLabel,
                    supportingText =
                        if (draft.duplicateLabel) {
                            { Text(stringResource(R.string.settings_event_types_duplicate_name)) }
                        } else {
                            null
                        },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Emoji entry — the same free-text field the booking form's custom type uses.
                OutlinedTextField(
                    value = draft.icon,
                    onValueChange = viewModel::setDraftIcon,
                    label = { Text(stringResource(R.string.settings_event_types_icon_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_event_types_color_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                ColorSwatchPicker(
                    entries =
                        viewModel.bookingColorsProvider.colors.mapNotNull { color ->
                            val fill = parseHexColor(color.hex) ?: return@mapNotNull null
                            val onFill = parseHexColor(color.onHex) ?: return@mapNotNull null
                            ColorSwatchEntry(color.key, fill, onFill, stringResource(color.labelRes))
                        },
                    selectedKey = draft.colorKey,
                    defaultSwatchName = stringResource(R.string.booking_color_default),
                    onSelect = viewModel::setDraftColor,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::saveDraft, enabled = draft.label.isNotBlank()) {
                Text(stringResource(R.string.common_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissDraft) {
                Text(stringResource(R.string.common_action_cancel))
            }
        },
    )
}
