package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.data.navigation.BookingExpenseDraft
import com.itsluminous.samaroh.core.data.navigation.CurrentScreenTracker
import com.itsluminous.samaroh.core.data.navigation.HelpScreenIds
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.EmptyStateCompact
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.SamarohCard
import com.itsluminous.samaroh.core.designsystem.component.SamarohFab
import com.itsluminous.samaroh.core.designsystem.theme.SamarohMotion
import com.itsluminous.samaroh.core.designsystem.theme.rememberReducedMotion
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.EventPartyName
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.feature.booking.domain.BookingCardActions
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.domain.EventsAgenda
import com.itsluminous.samaroh.feature.booking.domain.canReceivePaymentReminders
import com.itsluminous.samaroh.feature.booking.reminders.BookingReminderWorker
import com.itsluminous.samaroh.feature.booking.share.BookingShare
import com.itsluminous.samaroh.feature.booking.ui.currentLocale
import com.itsluminous.samaroh.feature.booking.ui.eventTypeLabel
import com.itsluminous.samaroh.feature.booking.ui.formatDate
import com.itsluminous.samaroh.feature.booking.ui.formatDateRange
import com.itsluminous.samaroh.feature.booking.ui.formatFullDate
import com.itsluminous.samaroh.feature.booking.ui.formatMonthYear
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Booking tab home (§4.1): month calendar with status pills, month summary card,
 * pending-confirmations card, agenda list, booking card sheet, record-payment sheet,
 * block-dates flow. Swipe left/right changes month; header opens the year/month picker.
 * A reminder deep link (ADR-098) is applied by the ViewModel from the app-wide holder:
 * the booking's date is shown (month / agenda) and its card opens.
 */
@Composable
fun BookingCalendarScreen(
    onAddBooking: (LocalDate?) -> Unit,
    onEditBooking: (String) -> Unit,
    viewModel: BookingCalendarViewModel = hiltViewModel(),
    eventTypes: EventTypeCatalog = viewModel.eventTypesProvider,
    bookingColors: BookingColorCatalog = viewModel.bookingColorsProvider,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val iconWatermarkAlpha by viewModel.iconWatermarkAlpha.collectAsState()
    val eventsView by viewModel.eventsView.collectAsState()
    val eventsAgenda by viewModel.eventsAgenda.collectAsState()
    val agendaScrollTarget by viewModel.agendaScrollTarget.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val restoreConflict by viewModel.restoreConflict.collectAsState()

    var monthPicker by remember { mutableStateOf(false) }
    var blockDialog by remember { mutableStateOf(false) }
    var blockDetails by remember { mutableStateOf<DateBlock?>(null) }
    var chooser by remember { mutableStateOf<DayTapResult.ShowBookings?>(null) }

    /** bookingId + prefilled due + optionally the reminder being answered. */
    var paymentSheet by remember { mutableStateOf<Triple<String, Long, String?>?>(null) }

    // Ensure the daily 09:00 reminder job exists (KEEP — idempotent). WorkManager is not
    // initialized in plain unit tests, hence the runCatching guard.
    LaunchedEffect(Unit) {
        runCatching { BookingReminderWorker.ensureScheduled(context) }
    }

    // Help/assistant context (ADR-108): the card and the record-payment sheet are guide
    // screens of their own — report whichever is on top; null once both are closed.
    val cardOpen = detail != null
    val paymentOpen = paymentSheet != null
    LaunchedEffect(cardOpen, paymentOpen) {
        CurrentScreenTracker.setOverlay(
            when {
                paymentOpen -> HelpScreenIds.RECORD_PAYMENT
                cardOpen -> HelpScreenIds.BOOKING_CARD
                else -> null
            },
        )
    }

    // Transient confirmations/errors ride the scaffold's snackbar (AGENTS convention; the
    // former toasts on this screen converged here with ADR-105).
    val snackbarHostState = remember { SnackbarHostState() }
    val paymentRecordedText = stringResource(R.string.booking_payment_recorded)
    val paymentDeletedText = stringResource(R.string.booking_payment_deleted)
    val invoiceFailedText = stringResource(R.string.booking_card_invoice_failed)
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            // showSnackbar suspends for the snackbar's lifetime — fork so a share intent
            // queued right behind a confirmation is never held back.
            when (event) {
                BookingEvent.PaymentRecorded -> launch { snackbarHostState.showSnackbar(paymentRecordedText) }
                BookingEvent.PaymentDeleted -> launch { snackbarHostState.showSnackbar(paymentDeletedText) }
                BookingEvent.InvoiceFailed -> launch { snackbarHostState.showSnackbar(invoiceFailedText) }
                is BookingEvent.SharePdf -> BookingShare.sharePdf(context, event.path)
                is BookingEvent.ShareText -> BookingShare.shareText(context, event.text)
            }
        }
    }

    val actor = state.actor
    val canCreate = actor?.let { it.isOwner || it.permissions.create } ?: false
    val canEdit = actor?.let { it.isOwner || it.permissions.edit } ?: false
    val canDelete = actor?.let { it.isOwner || it.permissions.delete } ?: false
    val canRecordPayment = actor?.let { it.isOwner || it.permissions.recordPayment } ?: false
    // Record expense against the event (ADR-101): `expenses.create`, carried on the actor.
    val canRecordExpense = actor?.let { it.isOwner || it.canRecordExpense } ?: false
    // Expenses-history amounts on the card follow `expenses.view_amounts` (ADR-106 §3b).
    val canViewExpenseAmounts = actor?.let { it.isOwner || it.canViewExpenseAmounts } ?: true
    // Invoice needs booking.view_amounts too (an invoice IS the amounts, ADR-039).
    val canViewAmounts = actor?.let { it.isOwner || it.permissions.viewAmounts } ?: true
    val canInvoice = (actor?.let { it.isOwner || it.permissions.generateInvoice } ?: false) && canViewAmounts
    // Payment reminders need record_payment AND view_amounts (ADR-097) — the VM already
    // yields no rows otherwise; the gate here is the same predicate, for the card shell.
    val canSeePaymentReminders = actor?.canReceivePaymentReminders ?: false

    Scaffold(
        // While the booking card (a separate sheet window) is open it hosts the snackbar.
        snackbarHost = { if (detail == null) SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (canCreate && state.business != null) {
                SamarohFab(onClick = { onAddBooking(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.booking_calendar_add))
                }
            }
        },
    ) { padding ->
        if (state.loaded && state.business == null) {
            EmptyState(
                icon = Icons.Filled.EventBusy,
                title = stringResource(R.string.booking_empty_no_business_title),
                message = stringResource(R.string.booking_empty_no_business_message),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        // ★ Events view (§4.1): the month grid swaps for the full agenda list — every
        // booking, grouped by date, anchored at today; the SAME booking-card sheet opens.
        if (eventsView) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.booking_calendar_events_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    CalendarOverflowMenu(
                        eventsView = true,
                        canEdit = canEdit,
                        onToggleView = { viewModel.setEventsView(false) },
                        onBlockDates = { blockDialog = true },
                    )
                }
                EventsAgendaList(
                    agenda = eventsAgenda,
                    eventTypes = eventTypes,
                    bookingColors = bookingColors,
                    presets = presets,
                    today = state.today,
                    onOpenBooking = viewModel::openBooking,
                    onLoadOlder = viewModel::loadOlderEvents,
                    onLoadNewer = viewModel::loadNewerEvents,
                    scrollTarget = agendaScrollTarget,
                    onScrolled = viewModel::onAgendaScrolled,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header: month navigation + year/month picker + overflow (block dates).
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        explanationRes = R.string.booking_calendar_prev_month,
                        onClick = viewModel::previousMonth,
                    )
                    Text(
                        text = formatMonthYear(state.month),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier =
                            Modifier
                                .weight(1f)
                                .clickable { monthPicker = true }
                                .semantics { heading() },
                    )
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        explanationRes = R.string.booking_calendar_next_month,
                        onClick = viewModel::nextMonth,
                    )
                    CalendarOverflowMenu(
                        eventsView = false,
                        canEdit = canEdit,
                        onToggleView = { viewModel.setEventsView(true) },
                        onBlockDates = { blockDialog = true },
                    )
                }

                // ★ Month summary card: "Received ₹X · Pending ₹Y" (§4.1) — received is
                // green (moneyIn), pending is red (moneyOut), per shared/brand/palette.md.
                SamarohCard {
                    Text(
                        text = stringResource(R.string.booking_summary_this_month),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    // ADR-103: one line each, shrink-to-fit — never wraps the card taller.
                    MonthSummaryRow(
                        receivedText =
                            stringResource(
                                R.string.booking_summary_received,
                                if (canViewAmounts) AmountFormatter.format(state.receivedPaise) else AmountFormatter.MASKED,
                            ),
                        pendingText =
                            stringResource(
                                R.string.booking_summary_pending,
                                if (canViewAmounts) AmountFormatter.format(state.pendingPaise) else AmountFormatter.MASKED,
                            ),
                    )
                }

                // ★ In-app pending-confirmations card — the reliable reminder path (§4.1).
                // §3 gate (ADR-097): a payment reminder is shown only to someone who can
                // both see the due and record the payment; otherwise the card is absent.
                if (state.pendingConfirmations.isNotEmpty() && canSeePaymentReminders) {
                    SamarohCard {
                        Text(
                            text = stringResource(R.string.booking_reminder_pending_card_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        state.pendingConfirmations.forEach { confirmation ->
                            val due = confirmation.reminder.amountDueSnapshotPaise
                            Text(
                                text =
                                    stringResource(
                                        R.string.booking_reminder_payment_question,
                                        confirmation.booking.customerName,
                                        AmountFormatter.format(due),
                                        eventTypeLabel(eventTypes, confirmation.booking.eventType),
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Row {
                                TextButton(onClick = { viewModel.confirmFullPayment(confirmation) }) {
                                    Text(stringResource(R.string.booking_reminder_action_yes_full))
                                }
                                TextButton(onClick = {
                                    paymentSheet = Triple(confirmation.booking.id, due, confirmation.reminder.id)
                                }) {
                                    Text(stringResource(R.string.booking_reminder_action_partial))
                                }
                                TextButton(onClick = { viewModel.snoozeReminder(confirmation) }) {
                                    Text(stringResource(R.string.booking_reminder_action_not_yet))
                                }
                            }
                        }
                    }
                }

                // ★ Tentative follow-ups due today (ADR-020): Confirm / Cancel / Snooze.
                // §3 gate: confirm/snooze mutate the booking (booking.edit); the cancel
                // action additionally needs booking.delete (cancel = tombstone).
                if (state.pendingFollowUps.isNotEmpty() && canEdit) {
                    SamarohCard {
                        Text(
                            text = stringResource(R.string.booking_reminder_follow_up_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        state.pendingFollowUps.forEach { followUp ->
                            Text(
                                text =
                                    stringResource(
                                        R.string.booking_reminder_follow_up_question,
                                        followUp.booking.customerName,
                                        eventTypeLabel(eventTypes, followUp.booking.eventType),
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Row {
                                TextButton(onClick = { viewModel.confirmTentativeBooking(followUp) }) {
                                    Text(stringResource(R.string.booking_reminder_action_confirm_booking))
                                }
                                if (canDelete) {
                                    TextButton(onClick = { viewModel.cancelTentativeBooking(followUp) }) {
                                        Text(stringResource(R.string.booking_card_action_cancel_booking))
                                    }
                                }
                                TextButton(onClick = { viewModel.snoozeFollowUp(followUp) }) {
                                    Text(stringResource(R.string.booking_reminder_action_snooze))
                                }
                            }
                        }
                    }
                }

                // Month grid with swipe navigation; month changes slide in the swipe
                // direction using the shared motion spec (no motion when reduced).
                val reducedMotion = rememberReducedMotion()
                state.grid?.let { grid ->
                    AnimatedContent(
                        targetState = grid,
                        contentKey = { it.month },
                        transitionSpec = {
                            val forward = targetState.month > initialState.month
                            SamarohMotion.slideEnter(reducedMotion, towardStart = forward) togetherWith
                                SamarohMotion.slideExit(reducedMotion, towardStart = forward)
                        },
                        label = "month_grid",
                        modifier =
                            Modifier.pointerInput(state.month) {
                                var dragTotal = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dragTotal = 0f },
                                    onDragEnd = {
                                        if (dragTotal < -120f) {
                                            viewModel.nextMonth()
                                        } else if (dragTotal > 120f) {
                                            viewModel.previousMonth()
                                        }
                                    },
                                ) { _, dragAmount -> dragTotal += dragAmount }
                            },
                    ) { animatedGrid ->
                        CalendarGrid(
                            grid = animatedGrid,
                            locale = currentLocale(),
                            iconWatermarkAlpha = iconWatermarkAlpha,
                            bookingColors = bookingColors,
                            onDayTapped = { date ->
                                when (val result = viewModel.onDayTapped(date)) {
                                    // Booked date (even a single booking) → day sheet with
                                    // every booking + the Add-new-event row.
                                    is DayTapResult.ShowBookings -> chooser = result

                                    is DayTapResult.ShowBlock -> blockDetails = result.block
                                    is DayTapResult.AddBooking -> if (canCreate) onAddBooking(result.date)
                                }
                            },
                        )
                    }
                }

                // Agenda list of the selected month (§4.1) — cancelled struck through.
                Text(
                    text = stringResource(R.string.booking_calendar_agenda_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                if (state.agenda.isEmpty()) {
                    EmptyStateCompact(
                        icon = Icons.Filled.EventAvailable,
                        title = stringResource(R.string.booking_calendar_agenda_empty),
                        message = stringResource(R.string.booking_calendar_agenda_empty_hint),
                    )
                } else {
                    state.agenda.forEach { item ->
                        // Tinted row background per the resolved booking colour
                        // (ADR-030/031/032 chain) — replaces the former colour dot.
                        BookingAgendaRow(
                            booking = item.booking,
                            eventTypes = eventTypes,
                            bookingColors = bookingColors,
                            presets = presets,
                            onClick = { viewModel.openBooking(item.booking.id) },
                        )
                    }
                }
            }
        }
    }

    // ---- overlays ----

    if (monthPicker) {
        MonthPickerDialog(
            current = state.month,
            onDismiss = { monthPicker = false },
            onPick = {
                monthPicker = false
                viewModel.showMonth(it)
            },
        )
    }

    if (blockDialog) {
        BlockDatesDialog(
            today = state.today,
            onDismiss = { blockDialog = false },
            onSave = { start, end, reason ->
                blockDialog = false
                viewModel.blockDates(start, end, reason)
            },
        )
    }

    blockDetails?.let { block ->
        BlockDetailsDialog(
            block = block,
            canRemove = canEdit,
            onDismiss = { blockDetails = null },
            onRemove = {
                viewModel.removeBlock(block.id)
                blockDetails = null
            },
        )
    }

    chooser?.let { sheet ->
        BookingChooserSheet(
            date = sheet.date,
            bookings = state.bookings.filter { it.id in sheet.bookingIds },
            eventTypes = eventTypes,
            bookingColors = bookingColors,
            presets = presets,
            canCreate = canCreate,
            onDismiss = { chooser = null },
            onPick = {
                chooser = null
                viewModel.openBooking(it)
            },
            onAddNew = { date ->
                chooser = null
                onAddBooking(date)
            },
        )
    }

    detail?.let { current ->
        // ★ Prefilled, localized WhatsApp payment-reminder text (§4.1) — resolved here in
        // composition so all placeholders are locale-correct.
        // Record-expense notes pre-fill (ADR-101 / shared contract §1): the localized
        // event-type label and the dates exactly as the card prints them.
        val expenseNotesDefault =
            stringResource(
                R.string.expenses_entry_booking_notes_default,
                eventTypeLabel(eventTypes, current.booking.eventType),
                formatDateRange(current.booking.startDate, current.booking.endDate),
            )
        val whatsappMessage =
            stringResource(
                R.string.booking_whatsapp_reminder_text,
                current.booking.customerName,
                if (canViewAmounts) AmountFormatter.format(current.duePaise) else AmountFormatter.MASKED,
                eventTypeLabel(eventTypes, current.booking.eventType),
                formatDate(current.booking.startDate),
                state.business?.name.orEmpty(),
            )
        BookingCardSheet(
            detail = current,
            eventTypes = eventTypes,
            presets = presets,
            bookingColors = bookingColors,
            // Audit line: the CREATOR's name (bookings.created_by), never the viewer's.
            creatorName = current.creatorName ?: stringResource(R.string.booking_card_audit_added_unknown_member),
            actions =
                BookingCardActions.forBooking(
                    status = current.booking.status,
                    isMarker = current.isMarker,
                    canEdit = canEdit,
                    canDelete = canDelete,
                    canRecordPayment = canRecordPayment,
                    canInvoice = canInvoice,
                    canRecordExpense = canRecordExpense,
                ),
            canViewAmounts = canViewAmounts,
            onDismiss = viewModel::dismissBookingCard,
            onEdit = {
                viewModel.dismissBookingCard()
                onEditBooking(current.booking.id)
            },
            onRecordPayment = { paymentSheet = Triple(current.booking.id, current.duePaise, null) },
            // ADR-101/ADR-106: park the pre-fill in the core:data holder and close the card;
            // the shell switches to the Expenses tab, whose host opens the add-entry form.
            // The event party's name is ENGLISH, device-independent (contract §2.1).
            onRecordExpense = {
                viewModel.recordExpense(
                    BookingExpenseDraft(
                        bookingId = current.booking.id,
                        businessId = current.booking.businessId,
                        eventPartyName =
                            EventPartyName.build(
                                context = context,
                                eventType = current.booking.eventType,
                                startDate = current.booking.startDate,
                                customerName = current.booking.customerName,
                            ),
                        customerPhone =
                            current.booking.customerPhone
                                ?.trim()
                                ?.ifEmpty { null },
                        defaultNotes = expenseNotesDefault,
                    ),
                )
            },
            onInvoicePdf = { viewModel.shareInvoicePdf(current.booking.id) },
            onInvoiceText = { viewModel.shareInvoiceText(current.booking.id) },
            onWhatsApp = { BookingShare.whatsAppReminder(context, whatsappMessage) },
            onCancelBooking = { viewModel.cancelBooking(current.booking.id) },
            onRestoreBooking = { viewModel.requestRestore(current.booking.id) },
            onDeleteBooking = { viewModel.deleteBookingPermanently(current.booking.id) },
            onDeletePayment = viewModel::deletePayment,
            snackbarHostState = snackbarHostState,
            canViewExpenseAmounts = canViewExpenseAmounts,
        )
    }

    // ★ Non-blocking restore-conflict warning (ADR-054): same semantics as the
    // add-form's double-booking popup — warn, never block.
    restoreConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRestoreConflict,
            title = { Text(stringResource(R.string.booking_form_conflict_title)) },
            text = { Text(pluralStringResource(R.plurals.booking_form_conflict_message, conflict.count, conflict.count)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) {
                    Text(stringResource(R.string.booking_card_restore_conflict_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestoreConflict) {
                    Text(stringResource(R.string.booking_form_conflict_go_back))
                }
            },
        )
    }

    paymentSheet?.let { (bookingId, duePaise, reminderId) ->
        RecordPaymentSheet(
            duePaise = duePaise,
            today = state.today,
            onDismiss = { paymentSheet = null },
            onSave = { amountPaise, paidOn, method, notes ->
                paymentSheet = null
                viewModel.recordPayment(bookingId, amountPaise, paidOn, method, notes, reminderId)
            },
        )
    }
}

/**
 * The calendar's three-dots menu, shared by the month and events headers: the
 * month ⇄ events view toggle (label flips with the current mode) plus Block dates.
 */
@Composable
private fun CalendarOverflowMenu(
    eventsView: Boolean,
    canEdit: Boolean,
    onToggleView: () -> Unit,
    onBlockDates: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ExplainableIcon(
            icon = Icons.Filled.MoreVert,
            explanationRes = R.string.booking_calendar_more_options,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (eventsView) R.string.booking_calendar_month_view else R.string.booking_calendar_events_view,
                        ),
                    )
                },
                onClick = {
                    expanded = false
                    onToggleView()
                },
            )
            if (canEdit) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.booking_calendar_block_dates)) },
                    onClick = {
                        expanded = false
                        onBlockDates()
                    },
                )
            }
        }
    }
}

/**
 * Full agenda list (events view): windowed date groups, anchored at today; nearing
 * either edge grows the window (past above, future below) instead of loading every
 * booking eagerly. Tapping a row opens the same booking-card sheet as the month view.
 */
@Composable
private fun EventsAgendaList(
    agenda: BookingCalendarViewModel.EventsAgendaState,
    eventTypes: EventTypeCatalog,
    bookingColors: BookingColorCatalog,
    presets: List<EventType>,
    today: LocalDate,
    onOpenBooking: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    modifier: Modifier = Modifier,
    scrollTarget: LocalDate? = null,
    onScrolled: () -> Unit = {},
) {
    if (agenda.loaded && agenda.days.isEmpty()) {
        // Nothing to scroll to: consume a pending deep-link target instead of parking it.
        LaunchedEffect(scrollTarget) { if (scrollTarget != null) onScrolled() }
        EmptyStateCompact(
            icon = Icons.Filled.EventAvailable,
            title = stringResource(R.string.booking_calendar_events_empty),
            message = stringResource(R.string.booking_calendar_agenda_empty_hint),
            modifier = modifier,
        )
        return
    }

    val listState = rememberLazyListState()
    // One-shot initial anchor on today's group (process restore keeps the old position).
    var anchored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(agenda.days.isNotEmpty()) {
        if (!anchored && agenda.days.isNotEmpty()) {
            val index = EventsAgenda.flatAnchorIndex(agenda.days, today)
            if (index >= 0) listState.scrollToItem(index)
            anchored = true
        }
    }
    // Reminder deep link (ADR-098): once the (possibly grown) window covering the date
    // has loaded, jump to its group — or the nearest later one when the booking was
    // deleted and its date has no group.
    LaunchedEffect(scrollTarget, agenda.window, agenda.days) {
        val target = scrollTarget ?: return@LaunchedEffect
        val window = agenda.window ?: return@LaunchedEffect
        if (target < window.from || target > window.to) return@LaunchedEffect
        val index = EventsAgenda.flatIndexOfDate(agenda.days, target)
        if (index >= 0) listState.scrollToItem(index)
        anchored = true
        onScrolled()
    }
    // Edge triggers: keys keep the viewport stable when older items are PREPENDED.
    LaunchedEffect(listState, agenda.hasMorePast, agenda.hasMoreFuture) {
        snapshotFlow {
            listState.firstVisibleItemIndex to
                (
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: 0
                )
        }.collect { (first, last) ->
            if (first <= 1 && agenda.hasMorePast) onLoadOlder()
            if (agenda.hasMoreFuture && last >= listState.layoutInfo.totalItemsCount - 3) onLoadNewer()
        }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        agenda.days.forEach { day ->
            item(key = "d:${day.date}") {
                Text(
                    text = formatFullDate(day.date),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp)
                            .semantics { heading() },
                )
            }
            items(day.bookings, key = { "b:${it.id}" }) { booking ->
                // Tinted row background per the resolved booking colour
                // (ADR-030/031/032 chain) — replaces the former colour dot.
                BookingAgendaRow(
                    booking = booking,
                    eventTypes = eventTypes,
                    bookingColors = bookingColors,
                    presets = presets,
                    onClick = { onOpenBooking(booking.id) },
                )
            }
        }
    }
}
