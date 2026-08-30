package com.itsluminous.samaroh.feature.menu.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold

/** Menu tab home (§4.4): identity row, Settings, Reports, Members (owner only), About. */
@Composable
fun MenuHomeScreen(
    onOpenSettings: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenMembers: () -> Unit,
    onOpenAbout: () -> Unit,
    onSignedOut: () -> Unit = {},
    viewModel: MenuHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
        IdentityRow(email = state.signedInEmail, onSignOut = viewModel::onSignOutRequested)
        HorizontalDivider()
        MenuSectionRow(
            icon = Icons.Filled.Settings,
            titleRes = R.string.menu_section_settings,
            subtitleRes = R.string.menu_section_settings_subtitle,
            onClick = onOpenSettings,
        )
        HorizontalDivider()
        MenuSectionRow(
            icon = Icons.Filled.BarChart,
            titleRes = R.string.menu_section_reports,
            subtitleRes = R.string.menu_section_reports_subtitle,
            onClick = onOpenReports,
        )
        if (state.isOwner) {
            HorizontalDivider()
            MenuSectionRow(
                icon = Icons.Filled.Group,
                titleRes = R.string.menu_section_members,
                subtitleRes = R.string.menu_section_members_subtitle,
                onClick = onOpenMembers,
            )
        }
        HorizontalDivider()
        MenuSectionRow(
            icon = Icons.Filled.Info,
            titleRes = R.string.menu_section_about,
            subtitleRes = R.string.menu_section_about_subtitle,
            onClick = onOpenAbout,
        )
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
