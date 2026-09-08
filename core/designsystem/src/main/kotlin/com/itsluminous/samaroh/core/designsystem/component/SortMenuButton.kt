package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.itsluminous.samaroh.core.i18n.R

/** One row of [SortMenuButton]'s dropdown: a sortable order and its localized label. */
data class SortMenuEntry<T>(
    val value: T,
    val label: String,
)

/**
 * Compact list-sort control (ADR-069): a sort icon (long-press explanation per the
 * [ExplainableIcon] convention) that opens a dropdown of the given orders, the selected
 * one marked with a trailing checkmark. Generic over the order type so features pass
 * their own enum plus localized labels; the icon and menu behavior stay consistent
 * across every sortable list.
 */
@Composable
fun <T> SortMenuButton(
    entries: List<SortMenuEntry<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ExplainableIcon(
            icon = Icons.AutoMirrored.Filled.Sort,
            explanationRes = R.string.common_sort_open,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    trailingIcon = {
                        if (entry.value == selected) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(entry.value)
                    },
                )
            }
        }
    }
}
