package com.itsluminous.samaroh.feature.inventory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.TxnType
import com.itsluminous.samaroh.feature.inventory.domain.parseQuantity
import com.itsluminous.samaroh.feature.inventory.domain.parseRupeesToPaise
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Edit-transaction dialog (ADR-070): quantity, unit price (Add rows only — a Remove's
 * cost is always recomputed from the replayed FIFO lots), date and notes. Saving runs
 * the FIFO replay; a rejected save (negative stock somewhere in the history) keeps the
 * dialog open and shows the localized error inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionDialog(
    transaction: InventoryTransaction,
    showRejectedError: Boolean,
    onDismiss: () -> Unit,
    onSave: (InventoryTransaction) -> Unit,
) {
    var quantityText by remember { mutableStateOf(plainQuantity(transaction.quantity)) }
    var unitPriceText by remember { mutableStateOf(plainRupees(transaction.unitPricePaise)) }
    var notes by remember { mutableStateOf(transaction.notes.orEmpty()) }
    var date by remember { mutableStateOf(transaction.transactionDate.atZone(ZoneId.systemDefault()).toLocalDate()) }
    var quantityInvalid by remember { mutableStateOf(false) }
    var priceInvalid by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inventory_txn_edit_title)) },
        confirmButton = {
            TextButton(onClick = {
                val quantity = parseQuantity(quantityText)
                if (quantity == null) {
                    quantityInvalid = true
                    return@TextButton
                }
                val unitPricePaise =
                    when (transaction.transactionType) {
                        TxnType.ADD -> {
                            val parsed = parseRupeesToPaise(unitPriceText)
                            if (parsed == null) {
                                priceInvalid = true
                                return@TextButton
                            }
                            parsed
                        }
                        // A remove's cost is derived by the replay, never typed.
                        TxnType.REMOVE -> transaction.unitPricePaise
                    }
                onSave(
                    transaction.copy(
                        quantity = quantity,
                        unitPricePaise = unitPricePaise,
                        transactionDate = transactionInstantOn(date, transaction.transactionDate),
                        notes = notes.trim().ifEmpty { null },
                    ),
                )
            }) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = {
                        quantityText = it
                        quantityInvalid = false
                    },
                    label = { Text(stringResource(R.string.inventory_txn_quantity_label)) },
                    isError = quantityInvalid,
                    supportingText =
                        if (quantityInvalid) {
                            { Text(stringResource(R.string.inventory_txn_error_quantity_invalid)) }
                        } else {
                            null
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (transaction.transactionType == TxnType.ADD) {
                    OutlinedTextField(
                        value = unitPriceText,
                        onValueChange = {
                            unitPriceText = it
                            priceInvalid = false
                        },
                        label = { Text(stringResource(R.string.inventory_txn_unit_price_label)) },
                        isError = priceInvalid,
                        supportingText =
                            if (priceInvalid) {
                                { Text(stringResource(R.string.inventory_txn_error_price_invalid)) }
                            } else {
                                null
                            },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box {
                    OutlinedTextField(
                        value = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.inventory_txn_date_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Transparent tap target: a readOnly text field swallows clicks.
                    Box(modifier = Modifier.matchParentSize().clickable { showDatePicker = true })
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.inventory_txn_notes_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showRejectedError) {
                    Text(
                        text = stringResource(R.string.inventory_txn_error_history_negative),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )

    if (showDatePicker) {
        val pickerState =
            rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.common_action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * The edited [Instant]: the picked local date carrying over the ORIGINAL transaction's
 * time-of-day, so an unchanged date keeps the exact stored instant (and same-day
 * ordering among transactions never shuffles on an unrelated edit).
 */
private fun transactionInstantOn(
    date: LocalDate,
    original: Instant,
): Instant {
    val zone = ZoneId.systemDefault()
    val originalTime = original.atZone(zone).toLocalTime()
    return date.atTime(originalTime).atZone(zone).toInstant()
}

/** Locale-independent quantity prefill — the exact form [parseQuantity] accepts back. */
private fun plainQuantity(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

/**
 * Plain-rupees prefill for the price INPUT ("120" / "120.50") — the exact form
 * [parseRupeesToPaise] accepts back. This is form state, not money display; rendered
 * amounts elsewhere keep going through `AmountFormatter` (ADR-002).
 */
private fun plainRupees(paise: Long): String {
    val rupees = paise / 100
    val fraction = paise % 100
    return if (fraction == 0L) "$rupees" else "$rupees.${fraction.toString().padStart(2, '0')}"
}
