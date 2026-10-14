package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.designsystem.component.AmountText
import com.itsluminous.samaroh.core.designsystem.component.AmountTone
import com.itsluminous.samaroh.core.designsystem.component.ChipRow
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.MetadataText
import com.itsluminous.samaroh.core.designsystem.component.ScrollableSheetScaffold
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.model.PaymentMethod
import com.itsluminous.samaroh.core.model.displayIcon
import com.itsluminous.samaroh.feature.booking.domain.BookingCardActionState
import com.itsluminous.samaroh.feature.booking.domain.BookingCardNotes
import com.itsluminous.samaroh.feature.booking.domain.BookingColorFallback
import com.itsluminous.samaroh.feature.booking.domain.BookingExpensesHistory
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
 * MARKER bookings (ADR-041/ADR-044) have no money, so the whole payment surface —
 * amounts, payment history, record-payment / invoice / payment-reminder actions —
 * is omitted; only customer, dates, audit line, edit and cancel remain.
 * CANCELLED bookings (ADR-054) offer exactly two actions: Restore and Delete
 * permanently — [actions] carries the whole status × permission matrix.
 */
@Composable
internal fun BookingCardSheet(
    detail: BookingDetail,
    eventTypes: EventTypeCatalog,
    presets: List<EventType>,
    bookingColors: BookingColorCatalog,
    creatorName: String,
    actions: BookingCardActionState,
    canViewAmounts: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRecordPayment: () -> Unit,
    onRecordExpense: () -> Unit,
    onInvoicePdf: () -> Unit,
    onInvoiceText: () -> Unit,
    onWhatsApp: () -> Unit,
    onCancelBooking: () -> Unit,
    onRestoreBooking: () -> Unit,
    onDeleteBooking: () -> Unit,
    onDeletePayment: (BookingPayment) -> Unit = {},
    /**
     * Transient confirmations while the card is open (ADR-105). The sheet is its own
     * window above the screen's Scaffold, so a snackbar hosted there would be covered —
     * the card hosts the same state itself for as long as it is showing.
     */
    snackbarHostState: SnackbarHostState? = null,
    /**
     * `expenses.view_amounts` (ADR-106 §3b): masks the "Expenses history" amounts exactly
     * as the Expenses tab does — independent of the booking-side [canViewAmounts].
     */
    canViewExpenseAmounts: Boolean = true,
) {
    val booking = detail.booking
    val context = LocalContext.current
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var invoiceChooser by rememberSaveable { mutableStateOf(false) }
    // Payment awaiting delete confirmation (ADR-105); id only — the row is re-resolved
    // from [detail] so a payment that vanished meanwhile simply closes the dialog.
    var confirmDeletePaymentId by rememberSaveable { mutableStateOf<String?>(null) }

    // ADR-107: the card is a ScrollableSheetScaffold — header/customer/dates/notes/amounts/
    // histories/audit scroll in the middle; the ACTION ROW is a pinned footer so Record
    // payment / Record expense / Invoice / Share are reachable without scrolling even when
    // long notes + a payment history + an expenses history overflow a small phone.
    ScrollableSheetScaffold(
        onDismissRequest = onDismiss,
        // Snackbar pinned at the TOP (ADR-105): the sheet is its own window, so the host
        // lives in the sheet; pinned above the scroll region it is never scrolled away.
        header = snackbarHostState?.let { host -> { SnackbarHost(host, modifier = Modifier.fillMaxWidth()) } },
        footer = {
            BookingCardActionsFooter(
                actions = actions,
                onEdit = onEdit,
                onRecordPayment = onRecordPayment,
                onRecordExpense = onRecordExpense,
                onInvoice = { invoiceChooser = true },
                onWhatsApp = onWhatsApp,
                onCancel = { confirmCancel = true },
                onRestoreBooking = onRestoreBooking,
                onDelete = { confirmDelete = true },
            )
        },
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

        // Booking notes (the free-text `bookings.notes` column — NOT the Notes tab):
        // mirrors web's BookingDetail — plain text right under the dates, ungated (no
        // permission key on either platform), shown for markers too, and omitted
        // entirely when blank. ADR-092. Rendered as CONTENT (bodyMedium/onSurface) so it
        // is visibly stronger than the metadata audit line below. ADR-093.
        BookingCardNotes.displayNotes(booking.notes)?.let { notes ->
            Text(
                text = notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().testTag("booking_card_notes"),
            )
        }

        if (!detail.isMarker) {
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
                    modifier = Modifier.testTag("booking_card_due"),
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
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("booking_card_payment_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Payment line mirrors web: "date · method[ · notes]".
                        Text(
                            text =
                                BookingCardNotes.paymentLine(
                                    date = formatDate(payment.paidOn),
                                    method = paymentMethodLabel(payment.method),
                                    notes = payment.notes,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        AmountText(amountPaise = payment.amountPaise, tone = AmountTone.MONEY_IN, masked = !canViewAmounts)
                        // Delete payment (ADR-105): same gate as recording; hidden otherwise.
                        if (actions.showDeletePayment) {
                            ExplainableIcon(
                                icon = Icons.Filled.Delete,
                                explanationRes = R.string.booking_payment_delete,
                                onClick = { confirmDeletePaymentId = payment.id },
                                modifier = Modifier.testTag("booking_card_delete_payment"),
                            )
                        }
                    }
                }
            }

            // Expenses history (ADR-106 §3b): directly below Payment history, same styling;
            // ENTIRELY absent (no divider, heading or empty state) when the booking has no
            // live linked expense — or when the member lacks expenses.view (the detail
            // then carries no rows). Read-only: no tap, no delete. Informational only —
            // the total is never subtracted from Due.
            if (detail.expenses.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.booking_card_expenses_history),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("booking_card_expenses_history"),
                )
                detail.expenses.forEach { expense ->
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("booking_card_expense_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = BookingCardNotes.expenseLine(date = formatDate(expense.expenseDate), notes = expense.notes),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // Received rows carry the ledger's money-in styling, paid rows money-out.
                        AmountText(
                            amountPaise = expense.amountPaise,
                            tone = if (expense.direction == ExpenseDirection.PAID) AmountTone.MONEY_OUT else AmountTone.MONEY_IN,
                            masked = !canViewExpenseAmounts,
                        )
                    }
                }
                val expensesTotal = BookingExpensesHistory.totalPaise(detail.expenses)
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("booking_card_expenses_total"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.booking_card_expenses_total),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    AmountText(
                        amountPaise = if (expensesTotal < 0) -expensesTotal else expensesTotal,
                        tone = if (expensesTotal >= 0) AmountTone.MONEY_OUT else AmountTone.MONEY_IN,
                        masked = !canViewExpenseAmounts,
                    )
                }
            }
        }

        // Audit line (§4.1 ★): who created the booking and when — metadata, ADR-093.
        MetadataText(
            text =
                stringResource(
                    R.string.booking_card_audit_added,
                    creatorName,
                    formatDate(booking.createdAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()),
                ),
            modifier = Modifier.testTag("booking_card_audit"),
        )
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
                }) {
                    // Destructive confirm reads in error red, same as the delete dialog.
                    Text(
                        text = stringResource(R.string.booking_card_action_cancel_booking),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.common_action_close)) }
            },
        )
    }

    // Permanent delete is irreversible on EVERY device — always confirmed (ADR-054).
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.booking_card_delete_confirm_title)) },
            text = { Text(stringResource(R.string.booking_card_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteBooking()
                }) {
                    Text(
                        text = stringResource(R.string.booking_card_action_delete_booking),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_action_close)) }
            },
        )
    }

    // Deleting a payment changes the due on every device — always confirmed (ADR-105).
    val paymentToDelete = confirmDeletePaymentId?.let { id -> detail.payments.firstOrNull { it.id == id } }
    if (paymentToDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDeletePaymentId = null },
            title = { Text(stringResource(R.string.booking_payment_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.booking_payment_delete_confirm_body,
                        if (canViewAmounts) AmountFormatter.format(paymentToDelete.amountPaise) else AmountFormatter.MASKED,
                        formatDate(paymentToDelete.paidOn),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeletePaymentId = null
                    onDeletePayment(paymentToDelete)
                }) {
                    Text(
                        text = stringResource(R.string.common_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePaymentId = null }) { Text(stringResource(R.string.common_action_cancel)) }
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

/**
 * The booking card's pinned action surface (ADR-107): icon actions, the Cancel-booking text
 * button and the Restore/Delete pair of a cancelled card — the whole status × permission
 * matrix lives in [actions]. Rendered OUTSIDE the scrolling region so it is always on screen.
 */
@Composable
private fun BookingCardActionsFooter(
    actions: BookingCardActionState,
    onEdit: () -> Unit,
    onRecordPayment: () -> Unit,
    onRecordExpense: () -> Unit,
    onInvoice: () -> Unit,
    onWhatsApp: () -> Unit,
    onCancel: () -> Unit,
    onRestoreBooking: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            if (actions.showEdit) {
                ExplainableIcon(icon = Icons.Filled.Edit, explanationRes = R.string.common_action_edit, onClick = onEdit)
            }
            if (actions.showRecordPayment) {
                ExplainableIcon(
                    icon = Icons.Filled.CurrencyRupee,
                    explanationRes = R.string.booking_card_action_record_payment,
                    onClick = onRecordPayment,
                )
            }
            // Record expense (ADR-101): beside Record payment; the Expenses-tab wallet
            // glyph says where the tap lands. Hidden without `expenses.create`.
            if (actions.showRecordExpense) {
                ExplainableIcon(
                    icon = Icons.Filled.AccountBalanceWallet,
                    explanationRes = R.string.booking_card_action_record_expense,
                    onClick = onRecordExpense,
                    modifier = Modifier.testTag("booking_card_record_expense"),
                )
            }
            if (actions.showInvoice) {
                ExplainableIcon(
                    icon = Icons.Filled.Print,
                    explanationRes = R.string.booking_card_action_invoice,
                    onClick = onInvoice,
                )
            }
            // The share action is specifically a payment REMINDER text (due amount
            // included), so markers — which have no dues — drop it too (ADR-044).
            if (actions.showWhatsAppReminder) {
                ExplainableIcon(
                    icon = Icons.Filled.Share,
                    explanationRes = R.string.booking_card_action_whatsapp,
                    onClick = onWhatsApp,
                )
            }
        }
        if (actions.showCancel) {
            TextButton(onClick = onCancel) {
                Text(
                    text = stringResource(R.string.booking_card_action_cancel_booking),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        // Cancelled card (ADR-054): the two ways forward — bring it back or
        // remove it for good — replace the whole active-action surface.
        // Visual weight tells them apart (ADR-054 addendum): Restore is the safe,
        // encouraged action → filled moneyIn-green button (the "You got" precedent
        // guarantees green even under dynamic color); Delete is destructive →
        // error-outlined button, matching the M3 destructive convention.
        if (actions.showRestore || actions.showDelete) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (actions.showRestore) {
                    Button(
                        onClick = onRestoreBooking,
                        colors = ButtonDefaults.buttonColors(containerColor = SamarohTheme.semanticColors.moneyIn),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(R.string.booking_card_action_restore_booking))
                    }
                }
                if (actions.showDelete) {
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(R.string.booking_card_action_delete_booking))
                    }
                }
            }
        }
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

    // IME handling (§6 UX round): the M3 sheet window never receives IME insets on
    // API 30+ (SOFT_INPUT_ADJUST_NOTHING; imePadding is a no-op inside it), so the
    // action row is pinned in the HEADER where the keyboard can never cover it; the
    // form fields scroll beneath it (ADR-107 scaffold).
    ScrollableSheetScaffold(
        onDismissRequest = onDismiss,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
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
        },
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
    // ADR-107 scaffold: a busy day's rows scroll; the date heading and the "Add new
    // event" row are pinned so the create action is reachable however long the list.
    ScrollableSheetScaffold(
        onDismissRequest = onDismiss,
        verticalArrangement = Arrangement.Top,
        header = {
            Text(
                text = formatFullDate(date),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
            )
        },
        footer =
            if (canCreate) {
                {
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
            } else {
                null
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth().testTag("day_sheet")) {
            bookings.forEach { booking ->
                BookingAgendaRow(
                    booking = booking,
                    eventTypes = eventTypes,
                    bookingColors = bookingColors,
                    presets = presets,
                    onClick = { onPick(booking.id) },
                )
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
