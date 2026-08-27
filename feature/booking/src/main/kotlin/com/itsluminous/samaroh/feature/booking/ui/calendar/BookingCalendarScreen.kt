package com.itsluminous.samaroh.feature.booking.ui.calendar

import android.widget.Toast
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.EmptyStateCompact
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.SamarohCard
import com.itsluminous.samaroh.core.designsystem.component.SamarohFab
import com.itsluminous.samaroh.core.designsystem.theme.SamarohMotion
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.designsystem.theme.rememberReducedMotion
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.displayIcon
import com.itsluminous.samaroh.feature.booking.domain.BookingColorCatalog
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.domain.EventsAgenda
import com.itsluminous.samaroh.feature.booking.reminders.BookingReminderWorker
import com.itsluminous.samaroh.feature.booking.share.BookingShare
import com.itsluminous.samaroh.feature.booking.ui.BookingColorDot
import com.itsluminous.samaroh.feature.booking.ui.currentLocale
import com.itsluminous.samaroh.feature.booking.ui.eventTypeLabel
import com.itsluminous.samaroh.feature.booking.ui.fill
import com.itsluminous.samaroh.feature.booking.ui.formatDate
import com.itsluminous.samaroh.feature.booking.ui.formatDateRange
import com.itsluminous.samaroh.feature.booking.ui.formatFullDate
import com.itsluminous.samaroh.feature.booking.ui.formatMonthYear
import java.time.LocalDate

/**
 * Booking tab home (§4.1): month calendar with status pills, month summary card,
 * pending-confirmations card, agenda list, booking card sheet, record-payment sheet,
 * block-dates flow. Swipe left/right changes month; header opens the year/month picker.
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

    var monthPicker by remember { mutableStateOf(false) }
    var blockDialog by remember { mutableStateOf(false) }
    var blockDetails by remember { mutableStateOf<DateBlock?>(null) }
    var chooser by remember { mutableStateOf<List<String>?>(null) }

    /** bookingId + prefilled due + optionally the reminder being answered. */
    var paymentSheet by remember { mutableStateOf<Triple<String, Long, String?>?>(null) }

    // Ensure the daily 09:00 reminder job exists (KEEP — idempotent). WorkManager is not
    // initialized in plain unit tests, hence the runCatching guard.
    LaunchedEffect(Unit) {
        runCatching { BookingReminderWorker.ensureScheduled(context) }
    }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                BookingEvent.PaymentRecorded ->
                    Toast.makeText(context, context.getString(R.string.booking_payment_recorded), Toast.LENGTH_SHORT).show()

                BookingEvent.InvoiceFailed ->
                    Toast.makeText(context, context.getString(R.string.booking_card_invoice_failed), Toast.LENGTH_SHORT).show()

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
    val canInvoice = actor?.let { it.isOwner || it.permissions.generateInvoice } ?: false

    Scaffold(
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
                    today = state.today,
                    onOpenBooking = viewModel::openBooking,
                    onLoadOlder = viewModel::loadOlderEvents,
                    onLoadNewer = viewModel::loadNewerEvents,
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
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text =
                                stringResource(
                                    R.string.booking_summary_received,
                                    AmountFormatter.format(state.receivedPaise),
                                ),
                            style = MaterialTheme.typography.titleMedium,
                            color = SamarohTheme.semanticColors.moneyIn,
                        )
                        Text(
                            text =
                                stringResource(
                                    R.string.booking_summary_pending,
                                    AmountFormatter.format(state.pendingPaise),
                                ),
                            style = MaterialTheme.typography.titleMedium,
                            color = SamarohTheme.semanticColors.moneyOut,
                        )
                    }
                }

                // ★ In-app pending-confirmations card — the reliable reminder path (§4.1).
                if (state.pendingConfirmations.isNotEmpty()) {
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
                if (state.pendingFollowUps.isNotEmpty()) {
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
                                TextButton(onClick = { viewModel.cancelTentativeBooking(followUp) }) {
                                    Text(stringResource(R.string.booking_card_action_cancel_booking))
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
                                    is DayTapResult.ShowBookings ->
                                        if (result.bookingIds.size == 1) {
                                            viewModel.openBooking(result.bookingIds.first())
                                        } else {
                                            chooser = result.bookingIds
                                        }

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
                        val booking = item.booking
                        val cancelled = booking.status == BookingStatus.CANCELLED
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.openBooking(booking.id) }
                                    .padding(vertical = 8.dp),
                        ) {
                            // Booking colour dot (ADR-030) — decorative; text carries the info.
                            bookingColors.byKey(booking.color)?.fill?.let { dotColor ->
                                BookingColorDot(color = dotColor, modifier = Modifier.padding(end = 8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${booking.displayIcon} ${eventTypeLabel(
                                        eventTypes,
                                        booking.eventType,
                                    )} - ${booking.customerName}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                                )
                                Text(
                                    text = formatDateRange(booking.startDate, booking.endDate),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = statusLabel(booking.status),
                                style = MaterialTheme.typography.labelLarge,
                                color = statusColor(booking.status),
                            )
                        }
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

    chooser?.let { ids ->
        BookingChooserSheet(
            bookings = state.bookings.filter { it.id in ids },
            eventTypes = eventTypes,
            onDismiss = { chooser = null },
            onPick = {
                chooser = null
                viewModel.openBooking(it)
            },
        )
    }

    detail?.let { current ->
        // ★ Prefilled, localized WhatsApp payment-reminder text (§4.1) — resolved here in
        // composition so all placeholders are locale-correct.
        val whatsappMessage =
            stringResource(
                R.string.booking_whatsapp_reminder_text,
                current.booking.customerName,
                AmountFormatter.format(current.duePaise),
                eventTypeLabel(eventTypes, current.booking.eventType),
                formatDate(current.booking.startDate),
                state.business?.name.orEmpty(),
            )
        BookingCardSheet(
            detail = current,
            eventTypes = eventTypes,
            bookingColors = bookingColors,
            creatorName = actor?.displayName ?: state.business?.ownerName.orEmpty(),
            canEdit = canEdit,
            canDelete = canDelete,
            canRecordPayment = canRecordPayment,
            canInvoice = canInvoice,
            onDismiss = viewModel::dismissBookingCard,
            onEdit = {
                viewModel.dismissBookingCard()
                onEditBooking(current.booking.id)
            },
            onRecordPayment = { paymentSheet = Triple(current.booking.id, current.duePaise, null) },
            onInvoicePdf = { viewModel.shareInvoicePdf(current.booking.id) },
            onInvoiceText = { viewModel.shareInvoiceText(current.booking.id) },
            onWhatsApp = { BookingShare.whatsAppReminder(context, whatsappMessage) },
            onCancelBooking = { viewModel.cancelBooking(current.booking.id) },
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
    today: LocalDate,
    onOpenBooking: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (agenda.loaded && agenda.days.isEmpty()) {
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
                val cancelled = booking.status == BookingStatus.CANCELLED
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenBooking(booking.id) }
                            .padding(vertical = 8.dp),
                ) {
                    // Booking colour dot (ADR-030) — decorative; text carries the info.
                    bookingColors.byKey(booking.color)?.fill?.let { dotColor ->
                        BookingColorDot(color = dotColor, modifier = Modifier.padding(end = 8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${booking.displayIcon} ${eventTypeLabel(eventTypes, booking.eventType)} - ${booking.customerName}",
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                        )
                        Text(
                            text = formatDateRange(booking.startDate, booking.endDate),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = statusLabel(booking.status),
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor(booking.status),
                    )
                }
            }
        }
    }
}
