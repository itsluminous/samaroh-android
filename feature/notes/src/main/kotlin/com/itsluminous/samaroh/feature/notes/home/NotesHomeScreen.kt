package com.itsluminous.samaroh.feature.notes.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.parseHexColor
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.NoteKind
import com.itsluminous.samaroh.feature.notes.domain.NoteCardData
import com.itsluminous.samaroh.feature.notes.domain.NotesSection
import com.itsluminous.samaroh.feature.notes.share.NotesShare
import kotlinx.coroutines.launch

/**
 * Keep-style notes home (ADR-077): hamburger drawer (Notes / Completed / Trash /
 * tag filters), live search bar, pinned-first staggered card grid, two bottom create
 * buttons (the expenses gave/got bar shape) and the note popup dialog.
 */
@Composable
fun NotesHomeScreen(
    modifier: Modifier = Modifier,
    viewModel: NotesHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is NotesEvent.ShareText -> NotesShare.shareText(context, event.text)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            NotesDrawer(
                state = state,
                onSection = { section ->
                    viewModel.selectSection(section)
                    scope.launch { drawerState.close() }
                },
                onTag = { tagId ->
                    viewModel.selectTag(tagId)
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            bottomBar = {
                // §3 gate: members without notes.create never see the create buttons.
                // Same bar shape as the expenses ledger's You gave / You got (ADR-077).
                if (state.canCreate && state.section == NotesSection.NOTES) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { viewModel.startCreate(NoteKind.NOTE) },
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            Text(stringResource(R.string.notes_home_create_note), style = MaterialTheme.typography.titleMedium)
                        }
                        Button(
                            onClick = { viewModel.startCreate(NoteKind.CHECKLIST) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            Text(stringResource(R.string.notes_home_create_checklist), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                // Top bar: hamburger + live search field (title/content/tag, ADR-077).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    ExplainableIcon(
                        icon = Icons.Filled.Menu,
                        explanationRes = R.string.notes_drawer_open,
                        onClick = { scope.launch { drawerState.open() } },
                    )
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        label = { Text(stringResource(R.string.notes_home_search_placeholder)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
                    )
                }
                if (state.section == NotesSection.TRASH) {
                    // "Items in trash are removed after 30 days" (ADR-077 purge sweep).
                    Text(
                        text = stringResource(R.string.notes_trash_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                NotesGridOrEmpty(state = state, viewModel = viewModel)
            }
        }
    }

    state.editor?.let { editor ->
        NoteEditorDialog(
            editor = editor,
            state = state,
            viewModel = viewModel,
        )
    }
    state.confirmPurgeId?.let { PurgeConfirmDialog(viewModel) }
}

@Composable
private fun NotesDrawer(
    state: NotesHomeState,
    onSection: (NotesSection) -> Unit,
    onTag: (String) -> Unit,
) {
    ModalDrawerSheet {
        Spacer(modifier = Modifier.height(12.dp))
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.notes_drawer_notes)) },
            icon = { Icon(Icons.AutoMirrored.Filled.StickyNote2, contentDescription = null) },
            selected = state.section == NotesSection.NOTES && state.selectedTagId == null,
            onClick = { onSection(NotesSection.NOTES) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.notes_drawer_completed)) },
            icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
            selected = state.section == NotesSection.COMPLETED,
            onClick = { onSection(NotesSection.COMPLETED) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.notes_drawer_trash)) },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            selected = state.section == NotesSection.TRASH,
            onClick = { onSection(NotesSection.TRASH) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        if (state.tags.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.notes_drawer_tags_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
            )
            state.tags.forEach { tag ->
                NavigationDrawerItem(
                    label = { Text(tag.name) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                    selected = state.selectedTagId == tag.id,
                    onClick = { onTag(tag.id) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun NotesGridOrEmpty(
    state: NotesHomeState,
    viewModel: NotesHomeViewModel,
) {
    val cards = state.pinned + state.others
    if (state.loaded && cards.isEmpty()) {
        when {
            state.sectionHasNotes || state.searchQuery.isNotBlank() || state.selectedTagId != null ->
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.notes_search_empty),
                    message = "",
                )
            state.section == NotesSection.COMPLETED ->
                EmptyState(
                    icon = Icons.Filled.CheckCircle,
                    title = stringResource(R.string.notes_completed_empty),
                    message = "",
                )
            state.section == NotesSection.TRASH ->
                EmptyState(
                    icon = Icons.Filled.Delete,
                    title = stringResource(R.string.notes_trash_empty),
                    message = "",
                )
            else ->
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.StickyNote2,
                    title = stringResource(R.string.notes_home_empty_title),
                    message = stringResource(R.string.notes_home_empty_message),
                )
        }
        return
    }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
    ) {
        // Pinned section above the others (ADR-077); headers only when both exist.
        val showHeaders = state.pinned.isNotEmpty() && state.others.isNotEmpty()
        if (showHeaders) {
            item(key = "header-pinned", span = StaggeredGridItemSpan.FullLine) {
                GridHeader(stringResource(R.string.notes_home_pinned_header))
            }
        }
        items(state.pinned, key = { "pinned-${it.note.id}" }) { card ->
            NoteCard(card, viewModel)
        }
        if (showHeaders) {
            item(key = "header-others", span = StaggeredGridItemSpan.FullLine) {
                GridHeader(stringResource(R.string.notes_home_others_header))
            }
        }
        items(state.others, key = { "other-${it.note.id}" }) { card ->
            NoteCard(card, viewModel)
        }
    }
}

@Composable
private fun GridHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/** Max checklist rows previewed on a card before the "+N" overflow line. */
private const val CARD_CHECKLIST_PREVIEW = 4

@Composable
private fun NoteCard(
    card: NoteCardData,
    viewModel: NotesHomeViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val palette: BookingColorCatalog = viewModel.bookingColorsProvider
    val color = palette.byKey(card.note.color)
    val container = color?.let { parseHexColor(it.hex) } ?: MaterialTheme.colorScheme.surfaceVariant
    val onContainer = color?.let { parseHexColor(it.onHex) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        onClick = { viewModel.openNote(card.note.id) },
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                card.note.title?.takeIf { it.isNotBlank() }?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } ?: Spacer(modifier = Modifier.weight(1f))
                if (card.note.pinned) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.notes_action_pin),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (card.note.kind == NoteKind.CHECKLIST) {
                card.note.checklist.take(CARD_CHECKLIST_PREVIEW).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Inline toggle (ADR-077) — a no-op without notes.edit.
                        Checkbox(
                            checked = item.done,
                            onCheckedChange =
                                if (state.canEdit) {
                                    { viewModel.toggleChecklistItemInline(card.note.id, item.id) }
                                } else {
                                    null
                                },
                        )
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val overflow = card.note.checklist.size - CARD_CHECKLIST_PREVIEW
                if (overflow > 0) {
                    Text(
                        text = "+$overflow",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                    )
                }
            } else {
                card.note.content?.takeIf { it.isNotBlank() }?.let { content ->
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (card.tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    card.tags.take(3).forEach { tag ->
                        Box(
                            modifier =
                                Modifier
                                    .background(
                                        color = onContainer.copy(alpha = 0.12f),
                                        shape = MaterialTheme.shapes.small,
                                    ).padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(text = tag.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PurgeConfirmDialog(viewModel: NotesHomeViewModel) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = viewModel::dismissPurge,
        title = { Text(stringResource(R.string.notes_trash_delete_forever_title)) },
        text = { Text(stringResource(R.string.notes_trash_delete_forever_message)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = viewModel::confirmPurge) {
                Text(stringResource(R.string.common_action_delete))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = viewModel::dismissPurge) {
                Text(stringResource(R.string.common_action_cancel))
            }
        },
    )
}
