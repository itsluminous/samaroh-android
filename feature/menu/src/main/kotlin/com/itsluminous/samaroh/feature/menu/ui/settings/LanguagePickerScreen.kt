package com.itsluminous.samaroh.feature.menu.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.itsluminous.samaroh.core.i18n.LocaleManager
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold

/**
 * Full-screen language picker (§4.4/§5): each language appears in its OWN script
 * (हिन्दी stays हिन्दी even when the app runs in English). Selection applies immediately
 * via [LocaleManager] per-app locales and persists across restarts (autoStoreLocales).
 */
@Composable
fun LanguagePickerScreen(onBack: () -> Unit) {
    var selectedTag by remember { mutableStateOf(LocaleManager.currentAppLocale()) }

    MenuScreenScaffold(titleRes = R.string.settings_language_picker_title, onBack = onBack) {
        LanguageRow(
            label = stringResource(R.string.settings_language_system),
            selected = selectedTag == null,
        ) {
            selectedTag = null
            LocaleManager.resetToSystemLocale()
        }
        HorizontalDivider()
        for (tag in LocaleManager.supportedLocales) {
            LanguageRow(
                label = languageDisplayName(tag),
                selected = selectedTag?.startsWith(tag) == true,
            ) {
                selectedTag = tag
                LocaleManager.setAppLocale(tag)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun LanguageRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        modifier = Modifier.clickable(onClick = onSelect),
    )
}

/** Own-script names come from locale-invariant catalog keys (both catalogs carry the same value). */
@Composable
private fun languageDisplayName(tag: String): String =
    when (tag) {
        "hi" -> stringResource(R.string.settings_language_name_hi)
        else -> stringResource(R.string.settings_language_name_en)
    }
