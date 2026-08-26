package com.itsluminous.samaroh.feature.inventory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.itsluminous.samaroh.core.designsystem.component.TypeAheadField
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.TxnType
import com.itsluminous.samaroh.feature.inventory.RecordTransactionViewModel
import com.itsluminous.samaroh.feature.inventory.TransactionFormError
import com.itsluminous.samaroh.feature.inventory.domain.formatQuantity

/**
 * Record-transaction dialog (§4.3): debounced fuzzy type-ahead item picker, Add/Remove
 * toggle, quantity (required), unit price (required for Add — removes cost out of FIFO
 * lots), notes, and the cannot-remove-more-than-stock validation.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RecordTransactionDialog(
    onDismiss: () -> Unit,
    viewModel: RecordTransactionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.saved) {
        if (state.saved) {
            // Reset BEFORE dismissing: the view model outlives the dialog (screen scope),
            // and a stale `saved` would instantly close the next opening (W2-B bug-fix).
            viewModel.consumeSaved()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inventory_txn_title)) },
        confirmButton = {
            TextButton(onClick = viewModel::save, enabled = !state.saving) {
                Text(stringResource(R.string.common_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_action_cancel))
            }
        },
        text = {
            // Scrolls when the IME shrinks the dialog; the action buttons live outside.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                TypeAheadField(
                    value = state.itemQuery,
                    onValueChange = viewModel::onItemQueryChange,
                    suggestions = state.suggestions.map { it.name },
                    onSuggestionSelected = viewModel::onItemSelected,
                    onQueryDebounced = viewModel::onItemQueryDebounced,
                    label = { Text(stringResource(R.string.inventory_txn_item_label)) },
                )
                val stock = state.availableStock
                val selected = state.selectedItem
                if (stock != null && selected != null) {
                    Text(
                        text =
                            stringResource(
                                R.string.inventory_txn_stock_available,
                                formatQuantity(stock),
                                unitDisplayLabel(selected.unit),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.type == TxnType.ADD,
                        onClick = { viewModel.onTypeChange(TxnType.ADD) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text(stringResource(R.string.inventory_txn_type_add))
                    }
                    SegmentedButton(
                        selected = state.type == TxnType.REMOVE,
                        onClick = { viewModel.onTypeChange(TxnType.REMOVE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(stringResource(R.string.inventory_txn_type_remove))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.quantityText,
                        onValueChange = viewModel::onQuantityChange,
                        label = { Text(stringResource(R.string.inventory_txn_quantity_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.type == TxnType.ADD) {
                        OutlinedTextField(
                            value = state.unitPriceText,
                            onValueChange = viewModel::onUnitPriceChange,
                            label = { Text(stringResource(R.string.inventory_txn_unit_price_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedTextField(
                    value = state.notes,
                    onValueChange = viewModel::onNotesChange,
                    label = { Text(stringResource(R.string.inventory_txn_notes_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { error ->
                    Text(
                        text = transactionErrorMessage(error, state.availableStock, selected?.unit),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
}

@Composable
private fun transactionErrorMessage(
    error: TransactionFormError,
    availableStock: Double?,
    unit: String?,
): String =
    when (error) {
        TransactionFormError.ITEM_REQUIRED -> stringResource(R.string.inventory_txn_error_item_required)
        TransactionFormError.QUANTITY_INVALID -> stringResource(R.string.inventory_txn_error_quantity_invalid)
        TransactionFormError.PRICE_INVALID -> stringResource(R.string.inventory_txn_error_price_invalid)
        TransactionFormError.INSUFFICIENT_STOCK ->
            stringResource(
                R.string.inventory_txn_error_insufficient_stock,
                formatQuantity(availableStock ?: 0.0),
                unit?.let { unitDisplayLabel(it) }.orEmpty(),
            )
    }
