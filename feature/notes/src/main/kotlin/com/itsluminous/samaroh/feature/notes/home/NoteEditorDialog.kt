package com.itsluminous.samaroh.feature.notes.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.ChipRow
import com.itsluminous.samaroh.core.designsystem.component.ColorSwatchDotsRow
import com.itsluminous.samaroh.core.designsystem.component.ColorSwatchEntry
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.TypeAheadField
import com.itsluminous.samaroh.core.designsystem.component.parseHexColor
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.NoteKind
import com.itsluminous.samaroh.core.model.NoteStatus
import com.itsluminous.samaroh.feature.notes.domain.NotesFilter
import com.itsluminous.samaroh.feature.notes.domain.RowDrag

/**
 * The note POPUP (ADR-077, widened per ADR-081, checklist model per ADR-082): a
 * NEAR-FULL-WIDTH dialog (`usePlatformDefaultWidth = false` + 95% width) that VIEWS a
 * note (read-only body observing the LIVE note + actions row: Share / Edit / Complete /
 * Restore / Delete) or EDITS it (title, body or non-editable checklist rows with
 * toggle/remove, one pinned Add-item field, whole-row long-press drag reorder, colour
 * swatches, tag row with type-ahead + create-on-the-fly). The pin toggle renders only
 * for EXISTING notes — never in the create popup (ADR-081). A member without
 * `notes.edit` gets the view-only popup: Share stays, every mutation is hidden; inline
 * checklist ticking follows the normalized `toggle_checklist` grant (ADR-082).
 */
@Composable
internal fun NoteEditorDialog(
    editor: NoteEditorState,
    state: NotesHomeState,
    viewModel: NotesHomeViewModel,
) {
    // Near-full-width popup (ADR-081): the platform default width made the editor
    // cramped; take 95% of the screen with slim margins instead.
    Dialog(
        onDismissRequest = viewModel::dismissEditor,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(0.95f),
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                HeaderRow(editor, state, viewModel)
                if (editor.editing) {
                    EditBody(editor, viewModel)
                } else {
                    ViewBody(editor, state, viewModel)
                }
                TagsRow(editor, viewModel)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (editor.editing) {
                    EditActions(viewModel)
                } else {
                    ViewActions(editor, state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(
    editor: NoteEditorState,
    state: NotesHomeState,
    viewModel: NotesHomeViewModel,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (editor.editing) {
            OutlinedTextField(
                value = editor.title,
                onValueChange = viewModel::setEditorTitle,
                label = { Text(stringResource(R.string.notes_editor_title_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = editor.title.ifBlank { stringResource(R.string.notes_editor_title_hint) },
                style = MaterialTheme.typography.titleLarge,
                color =
                    if (editor.title.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.weight(1f),
            )
        }
        if (pinToggleVisible(state.canEdit, editor)) {
            // Pin toggle (ADR-077): buffered while editing, immediate in view mode.
            ExplainableIcon(
                icon = if (editor.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                explanationRes = if (editor.pinned) R.string.notes_action_unpin else R.string.notes_action_pin,
                onClick = {
                    if (editor.editing) {
                        viewModel.toggleEditorPin()
                    } else {
                        editor.noteId?.let(viewModel::togglePin)
                    }
                },
            )
        }
        ExplainableIcon(
            icon = Icons.Filled.Close,
            explanationRes = R.string.common_action_close,
            onClick = viewModel::dismissEditor,
        )
    }
}

@Composable
private fun ViewBody(
    editor: NoteEditorState,
    state: NotesHomeState,
    viewModel: NotesHomeViewModel,
) {
    if (editor.kind == NoteKind.CHECKLIST) {
        // Blank-text items never render in the read-only popup (phantom-row guard).
        editor.items.filter { it.text.isNotBlank() }.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = item.done,
                    onCheckedChange =
                        if (state.canToggleChecklist) {
                            { editor.noteId?.let { id -> viewModel.toggleChecklistItemInline(id, item.id) } }
                        } else {
                            null
                        },
                )
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    // Done items read struck-through (ADR-082) — same on cards.
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    color =
                        if (item.done) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
            }
        }
    } else if (editor.content.isNotBlank()) {
        Text(
            text = editor.content,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

/**
 * Pin-toggle visibility in the popup header (ADR-081): NEVER in the CREATE popup
 * ([NoteEditorState.noteId] == null) — creating starts unpinned, decluttering the
 * cramped header. The remaining pin surfaces: this toggle in the VIEW-mode popup and
 * the edit popup of an existing note, plus the read-only pinned badge on grid cards.
 */
internal fun pinToggleVisible(
    canEdit: Boolean,
    editor: NoteEditorState,
): Boolean = canEdit && editor.status == NoteStatus.ACTIVE && editor.noteId != null

/**
 * "Tags" section-label visibility in the popup (feedback batch): EDIT mode only, where
 * it heads the removable chips + type-ahead as one "manage tags" section. The VIEW
 * popup shows the disabled chips bare — they are self-evident as tags, and a label
 * above an empty chip row (untagged note) would dangle.
 */
internal fun tagsSectionLabelVisible(editor: NoteEditorState): Boolean = editor.editing

@Composable
private fun EditBody(
    editor: NoteEditorState,
    viewModel: NotesHomeViewModel,
) {
    if (editor.kind == NoteKind.CHECKLIST) {
        ChecklistEditRows(editor, viewModel)
        // ONE pinned "Add item" field below the rows (ADR-082): Enter appends the
        // typed text as a new row, clears the field and KEEPS focus (the default
        // keyboard action is not invoked), so consecutive items type straight in.
        OutlinedTextField(
            value = editor.newItemText,
            onValueChange = viewModel::setNewItemText,
            label = { Text(stringResource(R.string.notes_editor_add_item)) },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.commitNewItem() }),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    } else {
        // The note box is the editor's star (feedback batch): the compact dot picker
        // freed vertical space — give it to the content field.
        OutlinedTextField(
            value = editor.content,
            onValueChange = viewModel::setEditorContent,
            label = { Text(stringResource(R.string.notes_editor_content_hint)) },
            minLines = 8,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
    // Colour dots (ADR-030 palette; compact single-row variant — feedback batch).
    Text(
        text = stringResource(R.string.notes_picker_color_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    ColorSwatchDotsRow(
        entries =
            viewModel.bookingColorsProvider.colors.mapNotNull { color ->
                val fill = parseHexColor(color.hex) ?: return@mapNotNull null
                val onFill = parseHexColor(color.onHex) ?: return@mapNotNull null
                ColorSwatchEntry(color.key, fill, onFill, stringResource(color.labelRes))
            },
        selectedKey = editor.colorKey,
        defaultSwatchName = stringResource(R.string.notes_picker_color_default),
        onSelect = viewModel::setEditorColor,
    )
}

/** Pickup scale of the dragged checklist row (standard drag affordance, ADR-082). */
private const val DRAG_PICKUP_SCALE = 1.03f

/**
 * Checklist rows in EDIT mode (ADR-082, matching the web track): items are
 * NON-EDITABLE once added — each row is a checkbox, the plain item text and a compact
 * remove cross (to change a text: remove and re-add). LONG-PRESSING anywhere on the
 * ROW picks it up (haptic + shadow + slight scale); the row follows the finger, its
 * target slot advances on midpoint crossing ([RowDrag.targetIndexFor]) while
 * neighbours animate out of the way ([RowDrag.rowShift]), and the reorder commits
 * ONCE on drop via [NotesHomeViewModel.moveChecklistItem].
 */
@Composable
private fun ChecklistEditRows(
    editor: NoteEditorState,
    viewModel: NotesHomeViewModel,
) {
    val haptics = LocalHapticFeedback.current
    var draggedItemId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableIntStateOf(-1) }
    // Measured row heights by item id — the midpoint-crossing thresholds.
    val rowHeights = remember { mutableStateMapOf<String, Int>() }
    val items = editor.items
    val fromIndex = items.indexOfFirst { it.id == draggedItemId }
    val draggedHeight = draggedItemId?.let { rowHeights[it] } ?: 0
    items.forEachIndexed { index, item ->
        key(item.id) {
            val dragged = draggedItemId == item.id
            val shift =
                if (draggedItemId != null && !dragged && fromIndex >= 0 && targetIndex >= 0) {
                    RowDrag.rowShift(index, fromIndex, targetIndex, draggedHeight)
                } else {
                    0
                }
            // Neighbours slide while a drag is live; on drop the shift SNAPS back to
            // 0 in the same frame the model reorders, so nothing visually double-moves.
            val animatedShift by animateFloatAsState(
                targetValue = shift.toFloat(),
                animationSpec = if (draggedItemId != null) tween(150) else snap(),
                label = "checklistRowShift",
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { rowHeights[item.id] = it.height }
                        .zIndex(if (dragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragged) dragOffset else animatedShift
                            if (dragged) {
                                shadowElevation = 8.dp.toPx()
                                scaleX = DRAG_PICKUP_SCALE
                                scaleY = DRAG_PICKUP_SCALE
                            }
                        }.pointerInput(item.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggedItemId = item.id
                                    dragOffset = 0f
                                    targetIndex =
                                        viewModel.state.value.editor
                                            ?.items
                                            .orEmpty()
                                            .indexOfFirst { it.id == item.id }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    // The buffer order stays FIXED during the drag
                                    // (commit happens on drop) — recompute only the
                                    // hovered target slot from the accumulated offset.
                                    val current =
                                        viewModel.state.value.editor
                                            ?.items
                                            .orEmpty()
                                    val from = current.indexOfFirst { it.id == item.id }
                                    if (from >= 0) {
                                        targetIndex =
                                            RowDrag.targetIndexFor(from, dragOffset, current.map { rowHeights[it.id] ?: 0 })
                                    }
                                },
                                onDragEnd = {
                                    val current =
                                        viewModel.state.value.editor
                                            ?.items
                                            .orEmpty()
                                    val from = current.indexOfFirst { it.id == item.id }
                                    if (from >= 0 && targetIndex >= 0 && targetIndex != from) {
                                        viewModel.moveChecklistItem(item.id, targetIndex)
                                    }
                                    draggedItemId = null
                                    dragOffset = 0f
                                    targetIndex = -1
                                },
                                onDragCancel = {
                                    draggedItemId = null
                                    dragOffset = 0f
                                    targetIndex = -1
                                },
                            )
                        },
            ) {
                Checkbox(
                    checked = item.done,
                    onCheckedChange = { viewModel.toggleEditorChecklistItem(item.id) },
                )
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    // Done items strike through in the edit popup too (ADR-082).
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    color =
                        if (item.done) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier = Modifier.weight(1f),
                )
                // Compact remove cross (ADR-081): smaller than the dialog's 48dp top
                // close cross so the two read differently and the row saves space.
                ExplainableIcon(
                    icon = Icons.Filled.Close,
                    explanationRes = R.string.notes_editor_remove_item,
                    targetSize = 32.dp,
                    iconSize = 18.dp,
                    onClick = { viewModel.removeChecklistItem(item.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsRow(
    editor: NoteEditorState,
    viewModel: NotesHomeViewModel,
) {
    val liveState by viewModel.state.collectAsStateWithLifecycle()
    val selected = liveState.tags.filter { it.id in editor.tagIds }
    if (!tagsSectionLabelVisible(editor)) {
        // VIEW mode (feedback batch): NO section label — the disabled chips are
        // self-evident as tags, and a label above zero chips would dangle. The label
        // stays in EDIT mode only, where it heads the chips + type-ahead as one
        // "manage tags" section (see tagsSectionLabelVisible).
        if (selected.isNotEmpty()) {
            ChipRow(modifier = Modifier.padding(top = 12.dp)) {
                selected.forEach { tag -> FilterChip(selected = true, onClick = {}, enabled = false, label = { Text(tag.name) }) }
            }
        }
        return
    }
    Text(
        text = stringResource(R.string.notes_picker_tags_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    // Edit mode (feedback batch): the note's tags render as REMOVABLE chips; existing
    // tags are found via a debounced TYPE-AHEAD (never a full listing) with a
    // Create "x" suggestion for brand-new names.
    if (selected.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        ) {
            selected.forEach { tag ->
                val removeName = stringResource(R.string.notes_picker_tags_remove, tag.name)
                InputChip(
                    selected = true,
                    onClick = { viewModel.toggleEditorTag(tag.id) },
                    label = { Text(tag.name) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = removeName,
                            modifier = Modifier.size(InputChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
    }
    val debounced = editor.debouncedTagQuery.trim()
    val tagSuggestions = NotesFilter.tagSuggestions(liveState.tags, editor.tagIds, debounced)
    // Offer Create "x" when the typed name matches no live tag exactly.
    val offerCreate =
        debounced.isNotEmpty() && liveState.tags.none { it.name.equals(debounced, ignoreCase = true) }
    val createLabel = if (offerCreate) stringResource(R.string.notes_picker_tags_create, debounced) else null
    val suggestionLabels = tagSuggestions.map { it.name } + listOfNotNull(createLabel)
    TypeAheadField(
        value = editor.tagQuery,
        onValueChange = viewModel::setTagQuery,
        suggestions = suggestionLabels,
        onSuggestionSelected = { picked ->
            if (picked == createLabel) {
                viewModel.createTagNamed(debounced)
            } else {
                viewModel.selectTagSuggestion(picked)
            }
        },
        onQueryDebounced = viewModel::setDebouncedTagQuery,
        label = { Text(stringResource(R.string.notes_picker_tags_name_placeholder)) },
        queryOnBlank = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

@Composable
private fun EditActions(viewModel: NotesHomeViewModel) {
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = viewModel::dismissEditor) { Text(stringResource(R.string.common_action_cancel)) }
        TextButton(onClick = viewModel::saveEditor) { Text(stringResource(R.string.common_action_save)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewActions(
    editor: NoteEditorState,
    state: NotesHomeState,
    viewModel: NotesHomeViewModel,
) {
    val noteId = editor.noteId ?: return
    // FlowRow: four actions don't always fit one line (long localized labels wrap
    // to the next row instead of squeezing vertically).
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextButton(onClick = { viewModel.shareNote(noteId) }) { Text(stringResource(R.string.notes_action_share)) }
        when (editor.status) {
            NoteStatus.ACTIVE -> {
                if (state.canEdit) {
                    TextButton(onClick = viewModel::startEditing) { Text(stringResource(R.string.notes_action_edit)) }
                    TextButton(onClick = { viewModel.completeNote(noteId) }) { Text(stringResource(R.string.notes_action_complete)) }
                    TextButton(onClick = { viewModel.trashNote(noteId) }) { Text(stringResource(R.string.notes_action_delete)) }
                }
            }
            NoteStatus.COMPLETED -> {
                if (state.canEdit) {
                    // Un-complete → back to the active list (ADR-077).
                    TextButton(onClick = { viewModel.restoreNote(noteId) }) { Text(stringResource(R.string.notes_action_restore)) }
                    TextButton(onClick = { viewModel.trashNote(noteId) }) { Text(stringResource(R.string.notes_action_delete)) }
                }
            }
            NoteStatus.TRASHED -> {
                if (state.canEdit) {
                    TextButton(onClick = { viewModel.restoreNote(noteId) }) { Text(stringResource(R.string.notes_action_restore)) }
                }
                if (state.canDelete) {
                    // Delete forever — confirmed by the purge dialog (notes.delete gate).
                    TextButton(onClick = { viewModel.requestPurge(noteId) }) { Text(stringResource(R.string.notes_action_delete)) }
                }
            }
        }
    }
    if (editor.status == NoteStatus.TRASHED) {
        Text(
            text = stringResource(R.string.notes_trash_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
