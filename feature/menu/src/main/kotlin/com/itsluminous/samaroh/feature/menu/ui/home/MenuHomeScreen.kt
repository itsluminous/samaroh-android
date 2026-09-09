package com.itsluminous.samaroh.feature.menu.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold
import com.itsluminous.samaroh.feature.menu.ui.search.MenuScreenTarget
import com.itsluminous.samaroh.feature.menu.ui.search.MenuSearch
import com.itsluminous.samaroh.feature.menu.ui.search.MenuSearchTarget
import com.itsluminous.samaroh.feature.menu.ui.search.ResolvedMenuSearchEntry

/**
 * Menu tab home (§4.4): search bar over every menu destination (ADR-075), identity
 * row, Settings, Reports, Members (owner only), About. A non-blank query replaces the
 * section rows with live-filtered results that navigate straight to their destination.
 */
@Composable
fun MenuHomeScreen(
    onOpenMenuScreen: (MenuScreenTarget) -> Unit,
    onOpenReport: (String?) -> Unit,
    onSignedOut: () -> Unit = {},
    viewModel: MenuHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    // Static index resolved once (both locales — ADR-075); filtering is per-keystroke.
    val searchEntries = remember(context) { MenuSearch.resolve(context) }

    // One-shot: sign-out completed (session dropped, local data wiped) — the app shell
    // routes to the onboarding sign-in step with a cleared back stack (ADR-040).
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                MenuHomeEvent.SignedOut -> onSignedOut()
            }
        }
    }

    MenuScreenScaffold(titleRes = R.string.menu_home_title) {
        MenuSearchField(query = query, onQueryChange = { query = it })
        if (query.isBlank()) {
            IdentityRow(email = state.signedInEmail, onSignOut = viewModel::onSignOutRequested)
            HorizontalDivider()
            MenuSectionRow(
                icon = Icons.Filled.Settings,
                titleRes = R.string.menu_section_settings,
                subtitleRes = R.string.menu_section_settings_subtitle,
                onClick = { onOpenMenuScreen(MenuScreenTarget.SETTINGS) },
            )
            HorizontalDivider()
            MenuSectionRow(
                icon = Icons.Filled.BarChart,
                titleRes = R.string.menu_section_reports,
                subtitleRes = R.string.menu_section_reports_subtitle,
                onClick = { onOpenReport(null) },
            )
            if (state.isOwner) {
                HorizontalDivider()
                MenuSectionRow(
                    icon = Icons.Filled.Group,
                    titleRes = R.string.menu_section_members,
                    subtitleRes = R.string.menu_section_members_subtitle,
                    onClick = { onOpenMenuScreen(MenuScreenTarget.MEMBERS) },
                )
            }
            HorizontalDivider()
            MenuSectionRow(
                icon = Icons.Filled.Info,
                titleRes = R.string.menu_section_about,
                subtitleRes = R.string.menu_section_about_subtitle,
                onClick = { onOpenMenuScreen(MenuScreenTarget.ABOUT) },
            )
        } else {
            val results =
                MenuSearch.filter(
                    entries = searchEntries,
                    query = query,
                    isOwner = state.isOwner,
                    isSignedIn = state.signedInEmail != null,
                )
            if (results.isEmpty()) {
                Text(
                    text = stringResource(R.string.menu_search_no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                results.forEach { result ->
                    MenuSearchResultRow(
                        result = result,
                        onClick = {
                            when (val target = result.entry.target) {
                                is MenuSearchTarget.Screen -> onOpenMenuScreen(target.screen)
                                is MenuSearchTarget.Report -> onOpenReport(target.reportRouteArg)
                                MenuSearchTarget.SignOut -> viewModel.onSignOutRequested()
                            }
                        },
                    )
                }
            }
        }
    }

    if (state.showSignOutDialog) {
        SignOutConfirmDialog(
            pendingSyncCount = state.pendingSyncCount,
            busy = state.isSigningOut,
            onConfirm = viewModel::onSignOutConfirmed,
            onDismiss = viewModel::onSignOutDismissed,
        )
    }
}

/** The live search field at the top of the Menu tab (ADR-075). */
@Composable
private fun MenuSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.menu_search_placeholder)) },
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
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** One search result: destination title + breadcrumb of the screen it lives on. */
@Composable
private fun MenuSearchResultRow(
    result: ResolvedMenuSearchEntry,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(result.title) },
        supportingContent = result.context?.let { { Text(it) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Signed-in identity row (§4.4): shows the session email with a sign-out icon at the
 * right (ADR-040), or a localized "Not signed in" state — no icon — in
 * offline/no-account mode.
 */
@Composable
private fun IdentityRow(
    email: String?,
    onSignOut: () -> Unit,
) {
    ListItem(
        overlineContent =
            if (email != null) {
                { Text(stringResource(R.string.menu_identity_signed_in_as)) }
            } else {
                null
            },
        headlineContent = {
            Text(
                text = email ?: stringResource(R.string.menu_identity_not_signed_in),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
        trailingContent =
            if (email != null) {
                {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        explanationRes = R.string.menu_identity_sign_out,
                        onClick = onSignOut,
                    )
                }
            } else {
                null
            },
    )
}

/**
 * Sign-out confirmation (ADR-040). With unsynced outbox changes the body warns how many
 * would be lost (ICU plural); otherwise it reassures that account data re-downloads on
 * the next sign-in.
 */
@Composable
private fun SignOutConfirmDialog(
    pendingSyncCount: Int,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_sign_out_confirm_title)) },
        text = {
            val message =
                if (pendingSyncCount > 0) {
                    pluralStringResource(R.plurals.menu_sign_out_confirm_message_pending, pendingSyncCount, pendingSyncCount)
                } else {
                    stringResource(R.string.menu_sign_out_confirm_message)
                }
            Text(message)
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(stringResource(R.string.menu_sign_out_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.common_action_cancel))
            }
        },
    )
}

@Composable
private fun MenuSectionRow(
    icon: ImageVector,
    @StringRes titleRes: Int,
    @StringRes subtitleRes: Int,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(stringResource(subtitleRes)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier =
            androidx.compose.ui.Modifier
                .clickable(onClick = onClick),
    )
}
