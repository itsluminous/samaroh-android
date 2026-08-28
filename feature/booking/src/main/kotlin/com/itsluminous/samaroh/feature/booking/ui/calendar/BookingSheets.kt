package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.designsystem.component.AmountText
import com.itsluminous.samaroh.core.designsystem.component.AmountTone
import com.itsluminous.samaroh.core.designsystem.component.ChipRow
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.core.model.PaymentMethod
import com.itsluminous.samaroh.core.model.displayIcon
import com.itsluminous.samaroh.feature.booking.domain.BookingColorFallback
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.share.BookingShare
import com.itsluminous.samaroh.feature.booking.ui.BookingColorDot
import com.itsluminous.samaroh.feature.booking.ui.eventTypeLabel
import com.itsluminous.samaroh.feature.booking.ui.fill
import com.itsluminous.samaroh.feature.booking.ui.form.parseRupeesToPaise
import com.itsluminous.samaroh.feature.booking.ui.formatDate
import com.itsluminous.samaroh.feature.booking.ui.formatDateRange
import com.itsluminous.samaroh.feature.booking.ui.formatFullDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Bottom sheets and dialogs of the calendar screen (§4.1).

/**
 * The booking card (§4.1 — one card = customer + event + financials): status chip,
 * tap-to-call, amounts with bold red due, payment history, audit line, actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookingCardSheet(
    detail: BookingDetail,
    eventTypes: EventTypeCatalog,
    presets: List<EventType>,
    bookingColors: BookingColorCatalog,
    creatorName: String,
    canEdit: Boolean,
    canDelete: Boolean,
    canRecordPayment: Boolean,
    canInvoice: Boolean,
    canViewAmounts: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRecordPayment: () -> Unit,
    onInvoicePdf: () -> Unit,
    onInvoiceText: () -> Unit,
    onWhatsApp: () -> Unit,
    onCancelBooking: () -> Unit,
) {
    val booking = detail.booking
    val context = LocalContext.current
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var invoiceChooser by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Booking colour (ADR-030): dot announcing its localized colour name;
                // resolved via the fallback chain (ADR-031: explicit → type default).
                BookingColorFallback.effectiveColor(booking, bookingColors, presets)?.let { paletteColor ->
                    paletteColor.fill?.let { dotColor ->
                        val colorName = stringResource(paletteColor.labelRes)
                        BookingColorDot(
                            color = dotColor,
                            size = 14.dp,
                            modifier = Modifier.semantics { contentDescription = colorName },
                        )
                    }
                }
                Text(
                    text = "${booking.displayIcon} ${eventTypeLabel(eventTypes, booking.eventType)}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                SuggestionChip(onClick = {}, label = { Text(statusLabel(booking.status)) })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = booking.customerName, style = MaterialTheme.typography.titleMedium)
                    booking.customerPhone?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                booking.customerPhone?.let { phone ->
                    ExplainableIcon(
                        icon = Icons.Filled.Call,
                        explanationRes = R.string.booking_card_phone_call,
                        onClick = { BookingShare.dial(context, phone) },
                    )
                }
            }

            Column {
                Text(
                    text = stringResource(R.string.booking_card_dates_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = formatDateRange(booking.startDate, booking.endDate), style = MaterialTheme.typography.bodyLarge)
            }

            AmountRow(labelRes = R.string.booking_card_total_label, amountPaise = booking.totalAmountPaise, masked = !canViewAmounts)
            if (booking.securityDepositPaise > 0) {
                AmountRow(
                    labelRes = R.string.booking_card_deposit_label,
                    amountPaise = booking.securityDepositPaise,
                    masked = !canViewAmounts,
                )
            }
            AmountRow(
                labelRes = R.string.booking_card_paid_label,
                amountPaise = detail.paidPaise,
                tone = AmountTone.MONEY_IN,
                masked = !canViewAmounts,
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.booking_card_due_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // Due: auto-calculated, bold, red when > 0 (§4.1).
                AmountText(
                    amountPaise = detail.duePaise,
                    tone = if (detail.duePaise > 0) AmountTone.MONEY_OUT else AmountTone.NEUTRAL,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    masked = !canViewAmounts,
                )
            }

            HorizontalDivider()
            Text(text = stringResource(R.string.booking_card_payments_title), style = MaterialTheme.typography.titleSmall)
            if (detail.payments.isEmpty()) {
                Text(
                    text = stringResource(R.string.booking_card_no_payments),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                detail.payments.forEach { payment ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${formatDate(payment.paidOn)} · ${paymentMethodLabel(payment.method)}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        AmountText(amountPaise = payment.amountPaise, tone = AmountTone.MONEY_IN, masked = !canViewAmounts)
                    }
                }
            }

            // Audit line (§4.1 ★): who created the booking and when.
            Text(
                text =
                    stringResource(
                        R.string.booking_card_audit_added,
                        creatorName,
                        formatDate(booking.createdAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                if (canEdit) {
                    ExplainableIcon(icon = Icons.Filled.Edit, explanationRes = R.string.common_action_edit, onClick = onEdit)
                }
                if (canRecordPayment) {
                    ExplainableIcon(
                        icon = Icons.Filled.CurrencyRupee,
                        explanationRes = R.string.booking_card_action_record_payment,
                        onClick = onRecordPayment,
                    )
                }
                if (canInvoice) {
                    ExplainableIcon(
                        icon = Icons.Filled.Print,
                        explanationRes = R.string.booking_card_action_invoice,
                        onClick = { invoiceChooser = true },
                    )
                }
                ExplainableIcon(
                    icon = Icons.Filled.Share,
                    explanationRes = R.string.booking_card_action_whatsapp,
                    onClick = onWhatsApp,
                )
            }
            if (canDelete) {
                TextButton(onClick = { confirmCancel = true }) {
                    Text(
                        text = stringResource(R.string.booking_card_action_cancel_booking),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.booking_card_cancel_confirm_title)) },
            text = { Text(stringResource(R.string.booking_card_cancel_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    onCancelBooking()
                }) { Text(stringResource(R.string.booking_card_action_cancel_booking)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.common_action_close)) }
            },
        )
    }

    if (invoiceChooser) {
        AlertDialog(
            onDismissRequest = { invoiceChooser = false },
            title = { Text(stringResource(R.string.booking_card_action_invoice)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        invoiceChooser = false
                        onInvoicePdf()
                    }) { Text(stringResource(R.string.booking_card_invoice_pdf)) }
                    TextButton(onClick = {
                        invoiceChooser = false
                        onInvoiceText()
                    }) { Text(stringResource(R.string.booking_card_invoice_text)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { invoiceChooser = false }) { Text(stringResource(R.string.common_action_close)) }
            },
        )
    }
}

@Composable
private fun AmountRow(
    labelRes: Int,
    amountPaise: Long,
    tone: AmountTone = AmountTone.NEUTRAL,
    masked: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        AmountText(amountPaise = amountPaise, tone = tone, masked = masked)
    }
}

@Composable
internal fun paymentMethodLabel(method: PaymentMethod): String =
    stringResource(
        when (method) {
            PaymentMethod.CASH -> R.string.booking_payment_method_cash
            PaymentMethod.UPI -> R.string.booking_payment_method_upi
            PaymentMethod.BANK_TRANSFER -> R.string.booking_payment_method_bank_transfer
            PaymentMethod.CHEQUE -> R.string.booking_payment_method_cheque
            PaymentMethod.OTHER -> R.string.booking_payment_method_other
        },
    )

/** Record-payment sheet (§4.1): amount prefilled with due, date, method chips, notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordPaymentSheet(
    duePaise: Long,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (amountPaise: Long, paidOn: LocalDate, method: PaymentMethod, notes: String?) -> Unit,
) {
    var amountText by rememberSaveable { mutableStateOf(if (duePaise > 0) (duePaise / 100).toString() else "") }
    var date by rememberSaveable { mutableStateOf(today) }
    var method by rememberSaveable { mutableStateOf(PaymentMethod.CASH) }
    var notes by rememberSaveable { mutableStateOf("") }
    var amountError by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        // IME handling (§6 UX round): the M3 sheet window never receives IME insets on
        // API 30+ (SOFT_INPUT_ADJUST_NOTHING; imePadding is a no-op inside it), so the
        // action row is pinned in the HEADER where the keyboard can never cover it.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.booking_card_action_record_payment),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
                TextButton(onClick = {
                    val paise = parseRupeesToPaise(amountText)
                    if (paise <= 0) {
                        amountError = true
                    } else {
                        onSave(paise, date, method, notes.ifBlank { null })
                    }
                }) { Text(stringResource(R.string.common_action_save)) }
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        amountError = false
                    },
                    label = { Text(stringResource(R.string.booking_payment_amount)) },
                    isError = amountError,
                    supportingText = { if (amountError) Text(stringResource(R.string.booking_payment_invalid_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DateField(labelRes = R.string.booking_payment_date, date = date, onDateChange = { date = it })
                Text(text = stringResource(R.string.booking_payment_method), style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    PaymentMethod.entries.forEach { candidate ->
                        FilterChip(
                            selected = method == candidate,
                            onClick = { method = candidate },
                            label = { Text(paymentMethodLabel(candidate)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.booking_form_notes)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Block-dates dialog (§4.1 overflow action). */
@Composable
internal fun BlockDatesDialog(
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (start: LocalDate, end: LocalDate, reason: String?) -> Unit,
) {
    var start by rememberSaveable { mutableStateOf(today) }
    var end by rememberSaveable { mutableStateOf(today) }
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.booking_calendar_block_dates)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField(labelRes = R.string.booking_form_start_date, date = start, onDateChange = {
                    start = it
                    if (end.isBefore(it)) end = it
                })
                DateField(labelRes = R.string.booking_form_end_date, date = end, onDateChange = { end = it })
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.booking_block_reason_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(start, end, reason.ifBlank { null }) }) {
                Text(stringResource(R.string.common_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

/** Details of an existing block with a remove action (needs `booking.edit`). */
@Composable
internal fun BlockDetailsDialog(
    block: DateBlock,
    canRemove: Boolean,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.booking_calendar_blocked)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatDateRange(block.startDate, block.endDate), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = block.reason ?: stringResource(R.string.booking_block_no_reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (canRemove) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.booking_block_remove)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
        },
    )
}

/**
 * Day bottom sheet: EVERY booking on the tapped date (even a single one) as a tinted
 * agenda row, plus a final "Add new event" row (permission-gated) that opens the add
 * form prefilled with that date as start AND end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookingChooserSheet(
    date: LocalDate,
    bookings: List<com.itsluminous.samaroh.core.model.Booking>,
    eventTypes: EventTypeCatalog,
    bookingColors: BookingColorCatalog,
    presets: List<EventType>,
    canCreate: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onAddNew: (LocalDate) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .testTag("day_sheet"),
        ) {
            Text(
                text = formatFullDate(date),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
            )
            bookings.forEach { booking ->
                BookingAgendaRow(
                    booking = booking,
                    eventTypes = eventTypes,
                    bookingColors = bookingColors,
                    presets = presets,
                    onClick = { onPick(booking.id) },
                )
            }
            if (canCreate) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onAddNew(date) }
                            .padding(vertical = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.booking_calendar_add_new_event),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

/** Year/month picker dialog on the calendar header (§4.1). */
@Composable
internal fun MonthPickerDialog(
    current: java.time.YearMonth,
    onDismiss: () -> Unit,
    onPick: (java.time.YearMonth) -> Unit,
) {
    var year by rememberSaveable { mutableStateOf(current.year) }
    val locale =
        com.itsluminous.samaroh.feature.booking.ui
            .currentLocale()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.booking_calendar_pick_month)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        explanationRes = R.string.booking_calendar_prev_month,
                        onClick = { year -= 1 },
                    )
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        explanationRes = R.string.booking_calendar_next_month,
                        onClick = { year += 1 },
                    )
                }
                (1..12).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { monthNumber ->
                            val month = java.time.Month.of(monthNumber)
                            TextButton(
                                onClick = { onPick(java.time.YearMonth.of(year, monthNumber)) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(month.getDisplayName(java.time.format.TextStyle.SHORT, locale))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

/** Clickable read-only date field opening a Material date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(
    labelRes: Int,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = formatDate(date),
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(labelRes)) },
        modifier = modifier.fillMaxWidth().clickable { open = true },
        enabled = false,
        colors =
            androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
    if (open) {
        val state =
            rememberDatePickerState(
                initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }) { Text(stringResource(R.string.common_action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
