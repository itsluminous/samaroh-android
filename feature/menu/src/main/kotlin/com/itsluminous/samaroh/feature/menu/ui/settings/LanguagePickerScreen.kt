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
 * (हिन्दी stays हिन्दी even when the app runs in English; Hinglish is "Hinglish", ADR-110).
 * Selection applies immediately via [LocaleManager] per-app locales and persists across
 * restarts (autoStoreLocales). The ticked row is an EXACT tag match
 * ([LocaleManager.supportedTagOf]) — `hi-Latn` starts with `hi`, so a prefix test would
 * tick Hindi for Hinglish.
 */
@Composable
fun LanguagePickerScreen(onBack: () -> Unit) {
    var selectedTag by remember { mutableStateOf(LocaleManager.supportedTagOf(LocaleManager.currentAppLocale())) }

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
                label = stringResource(LocaleManager.settingsNameRes(tag)),
                selected = selectedTag == tag,
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
