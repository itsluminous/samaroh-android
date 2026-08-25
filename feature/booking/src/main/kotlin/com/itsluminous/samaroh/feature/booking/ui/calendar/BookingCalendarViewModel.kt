package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.invoice.InvoiceGenerator
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.PaymentMethod
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.feature.booking.domain.BookingActor
import com.itsluminous.samaroh.feature.booking.domain.BookingActorProvider
import com.itsluminous.samaroh.feature.booking.domain.CalendarMonthMapper
import com.itsluminous.samaroh.feature.booking.domain.DueCalculator
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.domain.PaymentReminderPlanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject

/** What tapping a calendar day should do (§4.1 tap routing). */
sealed interface DayTapResult {
    /** Booked date → bottom-sheet booking card (first) or chooser (several). */
    data class ShowBookings(
        val bookingIds: List<String>,
    ) : DayTapResult

    /** Blocked date → block details sheet. */
    data class ShowBlock(
        val block: DateBlock,
    ) : DayTapResult

    /** Empty date → Add form with start AND end pre-selected to the tapped date. */
    data class AddBooking(
        val date: LocalDate,
    ) : DayTapResult
}

/** Booking card sheet content (§4.1 "one card = customer + event + financials"). */
data class BookingDetail(
    val booking: Booking,
    val payments: List<BookingPayment>,
    val paidPaise: Long,
    val duePaise: Long,
)

data class PendingConfirmationUi(
    val reminder: PaymentReminder,
    val booking: Booking,
)

data class AgendaItem(
    val booking: Booking,
)

data class BookingCalendarUiState(
    val loaded: Boolean = false,
    val business: Business? = null,
    val month: YearMonth,
    val today: LocalDate,
    val grid: CalendarMonthMapper.MonthGrid? = null,
    val bookings: List<Booking> = emptyList(),
    val blocks: List<DateBlock> = emptyList(),
    /** Month summary card: Σ payments of the month's live bookings. */
    val receivedPaise: Long = 0,
    /** Month summary card: Σ outstanding dues of the month's live bookings. */
    val pendingPaise: Long = 0,
    /** Agenda list: the month's bookings including cancelled (struck through). */
    val agenda: List<AgendaItem> = emptyList(),
    /** In-app Pending-confirmations card rows (§4.1 — the reliable reminder path). */
    val pendingConfirmations: List<PendingConfirmationUi> = emptyList(),
    val actor: BookingActor? = null,
)

/** One-shot UI events (toasts / share launches). */
sealed interface BookingEvent {
    data object PaymentRecorded : BookingEvent

    data class SharePdf(
        val path: String,
    ) : BookingEvent

    data class ShareText(
        val text: String,
    ) : BookingEvent

    data object InvoiceFailed : BookingEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookingCalendarViewModel
    @Inject
    constructor(
        private val bookingRepository: BookingRepository,
        businessRepository: BusinessRepository,
        private val actorProvider: BookingActorProvider,
        private val invoiceGenerator: InvoiceGenerator,
        private val syncScheduler: SyncScheduler,
        val eventTypesProvider: EventTypeCatalog,
        private val clock: Clock,
    ) : ViewModel() {
        private val month = MutableStateFlow(YearMonth.now(clock))
        private val selectedBookingId = MutableStateFlow<String?>(null)

        private val events = Channel<BookingEvent>(Channel.BUFFERED)
        val eventFlow: Flow<BookingEvent> = events.receiveAsFlow()

        private val businessFlow: Flow<Business?> =
            businessRepository.businesses().map { list -> list.firstOrNull { it.deletedAt == null } }

        private val actorFlow: Flow<BookingActor?> =
            businessFlow.map { business -> business?.let { actorProvider.actorFor(it) } }

        private data class MonthData(
            val bookings: List<Booking>,
            val blocks: List<DateBlock>,
            val payments: List<BookingPayment>,
            val reminders: List<PaymentReminder>,
        )

        private val monthData: Flow<Pair<YearMonth, MonthData>?> =
            combine(businessFlow, month) { business, month -> business to month }
                .flatMapLatest { (business, month) ->
                    if (business == null) {
                        flowOf(null)
                    } else {
                        val from = month.atDay(1)
                        val to = month.atEndOfMonth()
                        bookingRepository
                            .bookingsBetween(business.id, from, to)
                            .flatMapLatest { bookings ->
                                combine(
                                    bookingRepository.dateBlocksBetween(business.id, from, to),
                                    bookingRepository.paymentsForBookings(bookings.map { it.id }),
                                    bookingRepository.duePendingReminders(business.id, LocalDate.now(clock)),
                                ) { blocks, payments, reminders ->
                                    month to MonthData(bookings, blocks, payments, reminders)
                                }
                            }
                    }
                }

        val uiState: StateFlow<BookingCalendarUiState> =
            combine(businessFlow, actorFlow, monthData) { business, actor, data ->
                val today = LocalDate.now(clock)
                if (business == null || data == null) {
                    BookingCalendarUiState(loaded = true, business = business, month = month.value, today = today, actor = actor)
                } else {
                    val (shownMonth, monthData) = data
                    buildState(business, actor, shownMonth, today, monthData)
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                BookingCalendarUiState(month = YearMonth.now(clock), today = LocalDate.now(clock)),
            )

        /** Booking card sheet content, reactive to payment changes. */
        val detail: StateFlow<BookingDetail?> =
            selectedBookingId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(null)
                    } else {
                        combine(
                            uiState.map { state -> state.bookings.firstOrNull { it.id == id } },
                            bookingRepository.paymentsForBooking(id),
                        ) { booking, payments ->
                            booking?.let {
                                val paid = payments.sumOf { p -> p.amountPaise }
                                BookingDetail(it, payments, paid, DueCalculator.duePaise(it, paid))
                            }
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        private suspend fun buildState(
            business: Business,
            actor: BookingActor?,
            shownMonth: YearMonth,
            today: LocalDate,
            data: MonthData,
        ): BookingCalendarUiState {
            val live = data.bookings.filter { it.status != BookingStatus.CANCELLED && it.deletedAt == null }
            val paymentsByBooking = data.payments.groupBy { it.bookingId }
            val received = live.sumOf { booking -> paymentsByBooking[booking.id].orEmpty().sumOf { it.amountPaise } }
            val pending =
                live.sumOf { booking ->
                    DueCalculator.duePaise(booking, paymentsByBooking[booking.id].orEmpty().sumOf { it.amountPaise })
                }
            val confirmations =
                data.reminders.mapNotNull { reminder ->
                    val booking =
                        data.bookings.firstOrNull { it.id == reminder.bookingId }
                            ?: bookingRepository.booking(reminder.bookingId)
                    booking?.takeIf { it.status != BookingStatus.CANCELLED }?.let { PendingConfirmationUi(reminder, it) }
                }
            return BookingCalendarUiState(
                loaded = true,
                business = business,
                month = shownMonth,
                today = today,
                grid = CalendarMonthMapper.map(shownMonth, today, data.bookings, data.blocks),
                bookings = data.bookings,
                blocks = data.blocks,
                receivedPaise = received,
                pendingPaise = pending,
                agenda =
                    data.bookings
                        .filter { it.deletedAt == null }
                        .sortedBy { it.startDate }
                        .map { AgendaItem(it) },
                pendingConfirmations = confirmations,
                actor = actor,
            )
        }

        // ---- calendar navigation ----

        fun nextMonth() = month.update { it.plusMonths(1) }

        fun previousMonth() = month.update { it.minusMonths(1) }

        fun showMonth(target: YearMonth) = month.update { target }

        // ---- tap routing ----

        fun onDayTapped(date: LocalDate): DayTapResult {
            val state = uiState.value
            val booked = CalendarMonthMapper.bookingsOn(state.bookings, date)
            if (booked.isNotEmpty()) return DayTapResult.ShowBookings(booked.map { it.id })
            val blocked = CalendarMonthMapper.blocksOn(state.blocks, date)
            if (blocked.isNotEmpty()) return DayTapResult.ShowBlock(blocked.first())
            return DayTapResult.AddBooking(date)
        }

        fun openBooking(id: String) {
            selectedBookingId.value = id
        }

        fun dismissBookingCard() {
            selectedBookingId.value = null
        }

        // ---- mutations ----

        /** Cancel booking: status → cancelled, date released; reminders dismissed (§4.1). */
        fun cancelBooking(bookingId: String) {
            viewModelScope.launch {
                val state = uiState.value
                val actor = state.actor ?: return@launch
                if (!(actor.isOwner || actor.permissions.delete)) return@launch
                val booking = bookingRepository.booking(bookingId) ?: return@launch
                val now = clock.instant()
                bookingRepository.saveBooking(
                    booking.copy(status = BookingStatus.CANCELLED, updatedBy = actor.userId, updatedAt = now),
                )
                bookingRepository
                    .remindersForBooking(bookingId)
                    .filter { it.status == ReminderStatus.PENDING }
                    .forEach { bookingRepository.saveReminder(it.copy(status = ReminderStatus.DISMISSED, updatedAt = now)) }
                selectedBookingId.value = null
                syncScheduler.requestImmediateSync()
            }
        }

        /**
         * Record-payment sheet save (§4.1). When the payment answers a pending reminder,
         * the reminder is confirmed and — if due remains — the next one chains at +7 days.
         */
        fun recordPayment(
            bookingId: String,
            amountPaise: Long,
            paidOn: LocalDate,
            method: PaymentMethod,
            notes: String?,
            answeringReminderId: String? = null,
        ) {
            viewModelScope.launch {
                val actor = uiState.value.actor ?: return@launch
                if (!(actor.isOwner || actor.permissions.recordPayment)) return@launch
                if (amountPaise <= 0) return@launch
                val booking = bookingRepository.booking(bookingId) ?: return@launch
                val now = clock.instant()
                bookingRepository.recordPayment(
                    BookingPayment(
                        id = UUID.randomUUID().toString(),
                        bookingId = bookingId,
                        businessId = booking.businessId,
                        amountPaise = amountPaise,
                        paidOn = paidOn,
                        method = method,
                        notes = notes?.ifBlank { null },
                        createdBy = actor.userId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                settleReminderAfterPayment(booking, answeringReminderId)
                events.trySend(BookingEvent.PaymentRecorded)
                syncScheduler.requestImmediateSync()
            }
        }

        private suspend fun settleReminderAfterPayment(
            booking: Booking,
            answeringReminderId: String?,
        ) {
            val now = clock.instant()
            val today = LocalDate.now(clock)
            val due = DueCalculator.duePaise(booking, bookingRepository.totalPaidPaise(booking.id))
            val pending =
                bookingRepository
                    .remindersForBooking(booking.id)
                    .filter { it.status == ReminderStatus.PENDING }
            if (pending.isEmpty()) return
            if (due <= 0) {
                pending.forEach { bookingRepository.saveReminder(it.copy(status = ReminderStatus.CONFIRMED, updatedAt = now)) }
                return
            }
            // Partial payment answering a reminder: confirm it and re-remind in 7 days (§4.1).
            val answered = pending.firstOrNull { it.id == answeringReminderId } ?: return
            bookingRepository.saveReminder(answered.copy(status = ReminderStatus.CONFIRMED, updatedAt = now))
            PaymentReminderPlanner
                .nextAfterAction(answered, due, today, { UUID.randomUUID().toString() }, now)
                ?.let { bookingRepository.saveReminder(it) }
        }

        /** "Yes, full" on the in-app pending-confirmations card. */
        fun confirmFullPayment(confirmation: PendingConfirmationUi) {
            viewModelScope.launch {
                val due =
                    DueCalculator.duePaise(
                        confirmation.booking,
                        bookingRepository.totalPaidPaise(confirmation.booking.id),
                    )
                if (due > 0) {
                    recordPayment(
                        bookingId = confirmation.booking.id,
                        amountPaise = due,
                        paidOn = LocalDate.now(clock),
                        method = PaymentMethod.OTHER,
                        notes = null,
                        answeringReminderId = confirmation.reminder.id,
                    )
                } else {
                    bookingRepository.saveReminder(
                        confirmation.reminder.copy(status = ReminderStatus.CONFIRMED, updatedAt = clock.instant()),
                    )
                }
            }
        }

        /** "Not yet" on the in-app pending-confirmations card: snooze + re-remind in 7 days. */
        fun snoozeReminder(confirmation: PendingConfirmationUi) {
            viewModelScope.launch {
                val now = clock.instant()
                bookingRepository.saveReminder(confirmation.reminder.copy(status = ReminderStatus.SNOOZED, updatedAt = now))
                val due =
                    DueCalculator.duePaise(
                        confirmation.booking,
                        bookingRepository.totalPaidPaise(confirmation.booking.id),
                    )
                PaymentReminderPlanner
                    .nextAfterAction(confirmation.reminder, due, LocalDate.now(clock), { UUID.randomUUID().toString() }, now)
                    ?.let { bookingRepository.saveReminder(it) }
                syncScheduler.requestImmediateSync()
            }
        }

        // ---- date blocks ----

        fun blockDates(
            start: LocalDate,
            end: LocalDate,
            reason: String?,
        ) {
            viewModelScope.launch {
                val state = uiState.value
                val business = state.business ?: return@launch
                val actor = state.actor ?: return@launch
                if (!(actor.isOwner || actor.permissions.edit)) return@launch
                val now = clock.instant()
                bookingRepository.saveDateBlock(
                    DateBlock(
                        id = UUID.randomUUID().toString(),
                        businessId = business.id,
                        startDate = minOf(start, end),
                        endDate = maxOf(start, end),
                        reason = reason?.ifBlank { null },
                        createdBy = actor.userId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                syncScheduler.requestImmediateSync()
            }
        }

        fun removeBlock(blockId: String) {
            viewModelScope.launch {
                val actor = uiState.value.actor ?: return@launch
                if (!(actor.isOwner || actor.permissions.edit)) return@launch
                bookingRepository.deleteDateBlock(blockId)
                syncScheduler.requestImmediateSync()
            }
        }

        // ---- invoice (frozen InvoiceGenerator contract, ADR-006) ----

        fun shareInvoicePdf(bookingId: String) {
            viewModelScope.launch {
                invoiceGenerator
                    .generateInvoicePdf(bookingId)
                    .fold(
                        onSuccess = { events.trySend(BookingEvent.SharePdf(it)) },
                        onFailure = { events.trySend(BookingEvent.InvoiceFailed) },
                    )
            }
        }

        fun shareInvoiceText(bookingId: String) {
            viewModelScope.launch {
                runCatching { invoiceGenerator.buildInvoiceText(bookingId) }
                    .fold(
                        onSuccess = { events.trySend(BookingEvent.ShareText(it)) },
                        onFailure = { events.trySend(BookingEvent.InvoiceFailed) },
                    )
            }
        }
    }
