package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.onFocusChanged
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
 *
 * Additive options (inventory parity wave):
 * - [queryOnBlank]: also fire [onQueryDebounced] for a blank query, letting the caller
 *   supply a browse-all list when nothing is typed.
 * - [expandOnFocus]: open the dropdown as soon as the field is focused (not only after
 *   a keystroke) — combined with [queryOnBlank] the full list appears on first tap.
 * - [suggestionSupportingText]: optional pre-localized secondary line per suggestion
 *   (e.g. "Available: 4 Kg").
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
    queryOnBlank: Boolean = false,
    expandOnFocus: Boolean = false,
    suggestionSupportingText: ((String) -> String?)? = null,
) {
    var userIsTyping by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val currentOnQueryDebounced by rememberUpdatedState(onQueryDebounced)

    // BUG-FIX (W2-B e2e): `value` is a plain parameter, not snapshot state — reading it
    // directly inside snapshotFlow observes nothing (the flow emitted once and went
    // silent, so the debounced query NEVER fired and no suggestions ever appeared).
    // rememberUpdatedState wraps it in a MutableState the snapshot system can track.
    val currentValue by rememberUpdatedState(value)

    LaunchedEffect(debounceMs, queryOnBlank) {
        snapshotFlow { currentValue }
            .debounce(debounceMs)
            .distinctUntilChanged()
            .collect { query -> if (query.isNotBlank() || queryOnBlank) currentOnQueryDebounced(query) }
    }

    val expanded = (userIsTyping || (expandOnFocus && isFocused)) && suggestions.isNotEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { /* opens only from typing/focus */ },
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
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
                    .fillMaxWidth()
                    .onFocusChanged { state -> isFocused = state.isFocused },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                userIsTyping = false
                isFocused = false
            },
        ) {
            suggestions.forEach { suggestion ->
                val supporting = suggestionSupportingText?.invoke(suggestion)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(suggestion)
                            if (supporting != null) {
                                Text(
                                    text = supporting,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        userIsTyping = false
                        isFocused = false
                        onSuggestionSelected(suggestion)
                    },
                )
            }
        }
    }
}
