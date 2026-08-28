package com.itsluminous.samaroh.feature.menu.ui.about

import android.widget.Toast
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

/** About screen (§4.4): version, source link, Donate via UPI, licenses, made-with-love. */
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

    // Short label only — the URL is the destination, not the copy.
    val sourceUrl = stringResource(R.string.menu_about_source_code_url)

    MenuScreenScaffold(titleRes = R.string.menu_about_title, onBack = onBack) {
        // Version row opens THIS build's release notes — the URL derives from
        // versionName (tag v<versionName>), so every release links itself automatically.
        ListItem(
            headlineContent = { Text(stringResource(R.string.common_app_name), style = MaterialTheme.typography.titleLarge) },
            supportingContent = { Text(stringResource(R.string.menu_about_version, versionName)) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            modifier = Modifier.clickable { uriHandler.openUri(releaseNotesUrl(sourceUrl, versionName)) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.menu_about_source_code)) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            modifier = Modifier.clickable { uriHandler.openUri(sourceUrl) },
        )
        HorizontalDivider()
        val donateUri = stringResource(R.string.menu_about_donate_upi_uri)
        val donateNoApp = stringResource(R.string.menu_about_donate_no_upi_app)
        ListItem(
            headlineContent = { Text(stringResource(R.string.menu_about_donate_upi)) },
            supportingContent = { Text(stringResource(R.string.menu_about_donate_upi_summary)) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            modifier =
                Modifier.clickable {
                    if (!UpiDonate.open(context, donateUri)) {
                        Toast.makeText(context, donateNoApp, Toast.LENGTH_SHORT).show()
                    }
                },
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
