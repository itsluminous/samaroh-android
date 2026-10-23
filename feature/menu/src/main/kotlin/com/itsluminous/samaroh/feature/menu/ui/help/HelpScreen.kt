package com.itsluminous.samaroh.feature.menu.ui.help

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.help.HelpLanguage
import com.itsluminous.samaroh.core.help.HelpScreen
import com.itsluminous.samaroh.core.help.HelpTask
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold

/**
 * In-app Help (ADR-108): the single-source guide rendered in the app language — screen
 * sections with their summary, each task collapsible into numbered steps, a "Show me"
 * button that lands on the task's screen, a share icon per section with the public help
 * page link, and a search box over everything (both languages). The `?` app-bar icon
 * opens it pre-scrolled to the current screen's section.
 *
 * Tasks the member cannot perform are not listed (hide-not-grey, filtered in the ViewModel).
 */
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    viewModel: HelpViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lang = HelpLanguage.of(LocalConfiguration.current.locales[0])
    val context = LocalContext.current
    val listState = rememberLazyListState()
    // Expanded tasks ("screen/task" keys); while searching every result shows its steps.
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    val shareTitle = stringResource(R.string.menu_help_share_link)

    MenuScreenScaffold(titleRes = R.string.common_menu_help, onBack = onBack, scrollable = false) { modifier ->
        Column(modifier = modifier.fillMaxSize()) {
            HelpSearchField(query = state.query, onQueryChange = viewModel::onQueryChange)
            val screens = state.screens ?: return@Column
            if (screens.isEmpty()) {
                Text(
                    text = stringResource(R.string.menu_help_no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }
            // The `?` affordance: scroll ONCE to the requested screen's section header.
            ScrollToInitialScreen(listState = listState, screens = screens, initialScreenId = state.initialScreenId)
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                screens.forEach { screen ->
                    item(key = "screen:${screen.id}") {
                        ScreenHeader(
                            screen = screen,
                            lang = lang,
                            onShare = {
                                val url = viewModel.pageUrl(screen, lang)
                                val send =
                                    Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, "${screen.title[lang]}\n$url")
                                context.startActivity(Intent.createChooser(send, shareTitle))
                            },
                        )
                    }
                    screen.tasks.forEach { task ->
                        val key = "${screen.id}/${task.id}"
                        item(key = "task:$key") {
                            TaskRow(
                                task = task,
                                lang = lang,
                                expanded = state.searching || key in expanded,
                                onToggle = { expanded = if (key in expanded) expanded - key else expanded + key },
                                onShowMe = { viewModel.showMe(task) },
                            )
                        }
                    }
                    item(key = "divider:${screen.id}") { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }
}

/** Scrolls to [initialScreenId]'s header once the list has the data (no-op afterwards). */
@Composable
private fun ScrollToInitialScreen(
    listState: LazyListState,
    screens: List<HelpScreen>,
    initialScreenId: String?,
) {
    var done by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialScreenId, screens.isNotEmpty()) {
        if (done || initialScreenId == null) return@LaunchedEffect
        val index = helpListIndexOfScreen(screens, initialScreenId) ?: return@LaunchedEffect
        listState.scrollToItem(index)
        done = true
    }
}

/**
 * Flat LazyColumn index of [screenId]'s header: every screen contributes 1 header +
 * its tasks + 1 divider. Null when the screen is not in the list (hidden by permission).
 */
internal fun helpListIndexOfScreen(
    screens: List<HelpScreen>,
    screenId: String,
): Int? {
    var index = 0
    for (screen in screens) {
        if (screen.id == screenId) return index
        index += 1 + screen.tasks.size + 1
    }
    return null
}

@Composable
private fun HelpSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.menu_help_search_placeholder)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon =
            if (query.isBlank()) {
                null
            } else {
                {
                    ExplainableIcon(
                        icon = Icons.Filled.Clear,
                        explanationRes = R.string.menu_search_clear,
                        onClick = { onQueryChange("") },
                    )
                }
            },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ScreenHeader(
    screen: HelpScreen,
    lang: String,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = screen.title[lang],
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = screen.summary[lang],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        ExplainableIcon(
            icon = Icons.Filled.Share,
            explanationRes = R.string.menu_help_share_link,
            onClick = onShare,
        )
    }
}

@Composable
private fun TaskRow(
    task: HelpTask,
    lang: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onShowMe: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = task.title[lang], style = MaterialTheme.typography.titleMedium)
                Text(
                    text = pluralStringResource(R.plurals.menu_help_step_count, task.steps.size, task.steps.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                task.steps.forEachIndexed { index, step ->
                    Row(modifier = Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.menu_help_step_number, (index + 1).toString()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(text = step[lang], style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
                FilledTonalButton(onClick = onShowMe, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Text(text = stringResource(R.string.menu_help_show_me), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Test hook for the preview-less module: the list keys the screen renders, in order. */
internal fun helpListKeys(screens: List<HelpScreen>): List<String> =
    screens.flatMap { screen ->
        listOf("screen:${screen.id}") + screen.tasks.map { "task:${screen.id}/${it.id}" } + "divider:${screen.id}"
    }
