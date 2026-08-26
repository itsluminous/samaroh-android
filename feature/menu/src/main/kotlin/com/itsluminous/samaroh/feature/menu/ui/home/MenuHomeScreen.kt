package com.itsluminous.samaroh.feature.menu.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold

/** Menu tab home (§4.4): identity row, Settings, Reports, Members (owner only), About. */
@Composable
fun MenuHomeScreen(
    onOpenSettings: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenMembers: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: MenuHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MenuScreenScaffold(titleRes = R.string.menu_home_title) {
        IdentityRow(email = state.signedInEmail)
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
}

/**
 * Read-only signed-in identity row (§4.4): shows the session email, or a localized
 * "Not signed in" state in offline/no-account mode.
 */
@Composable
private fun IdentityRow(email: String?) {
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
