package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** Default debounce for type-ahead suggestion queries (§4.2/§4.3: ~300 ms). */
const val TYPE_AHEAD_DEBOUNCE_MS = 300L

/**
 * Text field with a debounced type-ahead suggestion dropdown (§4.2 add-person,
 * §4.3 item picker). While the user types, [onQueryDebounced] fires at most once per
 * [debounceMs] window; the caller supplies the current [suggestions] (typically from a
 * repository search) and receives [onSuggestionSelected] taps.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun TypeAheadField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    onSuggestionSelected: (String) -> Unit,
    onQueryDebounced: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    debounceMs: Long = TYPE_AHEAD_DEBOUNCE_MS,
) {
    var userIsTyping by remember { mutableStateOf(false) }
    val currentOnQueryDebounced by rememberUpdatedState(onQueryDebounced)

    LaunchedEffect(debounceMs) {
        snapshotFlow { value }
            .debounce(debounceMs)
            .distinctUntilChanged()
            .collect { query -> if (query.isNotBlank()) currentOnQueryDebounced(query) }
    }

    val expanded = userIsTyping && suggestions.isNotEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { /* opens only from typing */ },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                userIsTyping = true
                onValueChange(it)
            },
            label = label,
            singleLine = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { userIsTyping = false },
        ) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        userIsTyping = false
                        onSuggestionSelected(suggestion)
                    },
                )
            }
        }
    }
}
