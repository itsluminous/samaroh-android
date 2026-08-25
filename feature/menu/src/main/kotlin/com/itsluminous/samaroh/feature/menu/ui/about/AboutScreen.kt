package com.itsluminous.samaroh.feature.menu.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold

/** URL is data (not user-visible copy) — kept as a constant, shown verbatim. */
private const val GITHUB_URL = "https://github.com/itsluminous/samaroh-android"

/** About screen (§4.4): version, GitHub link, licenses, made-with-love. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showLicenses by rememberSaveable { mutableStateOf(false) }

    val versionName =
        remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
        }

    MenuScreenScaffold(titleRes = R.string.menu_about_title, onBack = onBack) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.common_app_name), style = MaterialTheme.typography.titleLarge) },
            supportingContent = { Text(stringResource(R.string.menu_about_version, versionName)) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.menu_about_source_code)) },
            supportingContent = { Text(GITHUB_URL) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            modifier = Modifier.clickable { uriHandler.openUri(GITHUB_URL) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.menu_about_licenses)) },
            modifier = Modifier.clickable { showLicenses = true },
        )
        HorizontalDivider()
        Text(
            text = stringResource(R.string.menu_about_made_with_love),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp),
        )
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text(stringResource(R.string.menu_about_licenses)) },
            text = { Text(stringResource(R.string.menu_about_licenses_body)) },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) {
                    Text(stringResource(R.string.common_action_close))
                }
            },
        )
    }
}
