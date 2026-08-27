package com.itsluminous.samaroh.feature.inventory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.itsluminous.samaroh.core.designsystem.component.TypeAheadField
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.TxnType
import com.itsluminous.samaroh.feature.inventory.RecordTransactionViewModel
import com.itsluminous.samaroh.feature.inventory.SavedTransaction
import com.itsluminous.samaroh.feature.inventory.TransactionFormError
import com.itsluminous.samaroh.feature.inventory.domain.formatQuantity
import com.itsluminous.samaroh.feature.inventory.domain.parseQuantity
import com.itsluminous.samaroh.feature.inventory.domain.parseRupeesToPaise
import kotlin.math.roundToLong

/**
 * Record-transaction dialog (§4.3): type-ahead item picker (full list when blank,
 * substring for short queries, fuzzy from 3 characters; Remove mode offers only
 * in-stock items with availability notes), Add/Remove toggle, quantity (required),
 * unit price (required for Add — removes cost out of FIFO lots) with a live total
 * preview, notes, and live cannot-remove-more-than-stock validation.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RecordTransactionDialog(
    onDismiss: () -> Unit,
    preselectedItemId: String? = null,
    initialType: TxnType = TxnType.ADD,
    onSaved: (SavedTransaction) -> Unit = {},
    viewModel: RecordTransactionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val currentOnSaved by rememberUpdatedState(onSaved)

    LaunchedEffect(preselectedItemId, initialType) {
        if (preselectedItemId != null) viewModel.preselectItem(preselectedItemId, initialType)
    }

    LaunchedEffect(state.saved) {
        state.saved?.let { saved ->
            // Reset BEFORE dismissing: the view model outlives the dialog (screen scope),
            // and a stale `saved` would instantly close the next opening (W2-B bug-fix).
            viewModel.consumeSaved()
            currentOnSaved(saved)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inventory_txn_title)) },
        confirmButton = {
            TextButton(
                onClick = viewModel::save,
                enabled = !state.saving && state.error != TransactionFormError.INSUFFICIENT_STOCK,
            ) {
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
                // Availability notes per suggestion (Remove mode), pre-localized here
                // because the dropdown lambda is not a composable context.
                val availabilityByName =
                    if (state.type == TxnType.REMOVE) {
                        state.suggestions.associate { item ->
                            item.name to
                                stringResource(
                                    R.string.inventory_txn_option_available,
                                    formatQuantity(state.stockByItemId[item.id] ?: 0.0),
                                    unitDisplayLabel(item.unit),
                                )
                        }
                    } else {
                        emptyMap()
                    }
                TypeAheadField(
                    value = state.itemQuery,
                    onValueChange = viewModel::onItemQueryChange,
                    suggestions = state.suggestions.map { it.name },
                    onSuggestionSelected = viewModel::onItemSelected,
                    onQueryDebounced = viewModel::onItemQueryDebounced,
                    label = { Text(stringResource(R.string.inventory_txn_item_label)) },
                    queryOnBlank = true,
                    expandOnFocus = true,
                    suggestionSupportingText = { name -> availabilityByName[name] },
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
                        isError = state.error == TransactionFormError.INSUFFICIENT_STOCK,
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
                if (state.type == TxnType.ADD) {
                    TotalPricePreview(quantityText = state.quantityText, unitPriceText = state.unitPriceText)
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

/** Live "Total price: ₹X" box, shown as soon as quantity and unit price both parse. */
@Composable
private fun TotalPricePreview(
    quantityText: String,
    unitPriceText: String,
) {
    val quantity = parseQuantity(quantityText) ?: return
    val unitPricePaise = parseRupeesToPaise(unitPriceText) ?: return
    val totalPaise = (quantity * unitPricePaise).roundToLong()
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.inventory_txn_total_preview, AmountFormatter.format(totalPaise)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
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
