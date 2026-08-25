package com.itsluminous.samaroh.feature.booking.ui.form

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.PaymentMethod
import com.itsluminous.samaroh.feature.booking.domain.BookingActor
import com.itsluminous.samaroh.feature.booking.domain.BookingActorProvider
import com.itsluminous.samaroh.feature.booking.domain.EventType
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

/** Validation / warning outcomes of a save attempt (§4.1). */
sealed interface FormBlocker {
    /** Customer name missing. */
    data object NameRequired : FormBlocker

    /** End date before start date. */
    data object EndBeforeStart : FormBlocker

    /** Non-blocking double-booking warning: save allowed via [BookingFormViewModel.saveAnyway]. */
    data class Conflict(
        val count: Int,
    ) : FormBlocker

    /** Blocked dates DO block; owners see an override button (§4.1). */
    data class BlockedDates(
        val canOverride: Boolean,
    ) : FormBlocker
}

data class BookingFormState(
    val loaded: Boolean = false,
    val editingId: String? = null,
    val eventTypes: List<EventType> = emptyList(),
    val eventType: EventType? = null,
    val customLabel: String = "",
    val customEmoji: String = "✨",
    val status: BookingStatus = BookingStatus.CONFIRMED,
    val customerName: String = "",
    val customerPhone: String = "",
    val startDate: LocalDate,
    val endDate: LocalDate,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    /** Raw rupee text as typed; parsed to paise on the fly. */
    val totalAmountText: String = "",
    val securityDepositText: String = "",
    val advanceText: String = "",
    val source: BookingSource? = null,
    val notes: String = "",
    val blocker: FormBlocker? = null,
    val saved: Boolean = false,
) {
    val totalAmountPaise: Long get() = parseRupeesToPaise(totalAmountText)
    val securityDepositPaise: Long get() = parseRupeesToPaise(securityDepositText)
    val advancePaise: Long get() = parseRupeesToPaise(advanceText)

    /** Live-updating, read-only due (§4.1): total − advance (edit mode shows card due instead). */
    val duePaise: Long get() = (totalAmountPaise - advancePaise).coerceAtLeast(0)
}

/** "1,200.50" → 120050 paise. Invalid input parses as 0 (field-level UX, not an error). */
fun parseRupeesToPaise(text: String): Long {
    val cleaned = text.trim().replace(",", "")
    if (cleaned.isEmpty()) return 0L
    return runCatching {
        java.math
            .BigDecimal(cleaned)
            .movePointRight(2)
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrDefault(0L)
}

@HiltViewModel
class BookingFormViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val bookingRepository: BookingRepository,
        private val businessRepository: BusinessRepository,
        private val actorProvider: BookingActorProvider,
        private val eventTypesProvider: EventTypeCatalog,
        private val syncScheduler: SyncScheduler,
        private val clock: Clock,
    ) : ViewModel() {
        private val editBookingId: String? = savedStateHandle.get<String?>("bookingId")?.ifBlank { null }
        private val prefillDate: LocalDate? =
            savedStateHandle.get<String?>("date")?.ifBlank { null }?.let { LocalDate.parse(it) }

        private val _state =
            MutableStateFlow(
                BookingFormState(
                    startDate = prefillDate ?: LocalDate.now(clock),
                    endDate = prefillDate ?: LocalDate.now(clock),
                ),
            )
        val state: StateFlow<BookingFormState> = _state.asStateFlow()

        private var actor: BookingActor? = null
        private var editing: Booking? = null

        init {
            viewModelScope.launch {
                val types = eventTypesProvider.eventTypes
                val business = businessRepository.businesses().first().firstOrNull { it.deletedAt == null }
                actor = business?.let { actorProvider.actorFor(it) }
                val existing = editBookingId?.let { bookingRepository.booking(it) }
                editing = existing
                _state.update { state ->
                    if (existing == null) {
                        state.copy(
                            loaded = true,
                            eventTypes = types,
                            eventType =
                                types.firstOrNull { it.key == "wedding" } ?: types.firstOrNull(),
                        )
                    } else {
                        val builtIn = types.firstOrNull { it.key == existing.eventType && !it.isCustom }
                        state.copy(
                            loaded = true,
                            editingId = existing.id,
                            eventTypes = types,
                            eventType = builtIn ?: types.firstOrNull { it.isCustom },
                            customLabel = if (builtIn == null) existing.eventType else "",
                            customEmoji = existing.eventIcon,
                            status = existing.status,
                            customerName = existing.customerName,
                            customerPhone = existing.customerPhone.orEmpty(),
                            startDate = existing.startDate,
                            endDate = existing.endDate,
                            startTime = existing.startTime,
                            endTime = existing.endTime,
                            totalAmountText = paiseToRupeeText(existing.totalAmountPaise),
                            securityDepositText = paiseToRupeeText(existing.securityDepositPaise),
                            source = existing.source,
                            notes = existing.notes.orEmpty(),
                        )
                    }
                }
            }
        }

        // ---- field updates ----

        fun setEventType(type: EventType) = _state.update { it.copy(eventType = type) }

        fun setCustomLabel(value: String) = _state.update { it.copy(customLabel = value) }

        fun setCustomEmoji(value: String) = _state.update { it.copy(customEmoji = value) }

        fun setStatus(status: BookingStatus) = _state.update { it.copy(status = status) }

        fun setCustomerName(value: String) = _state.update { it.copy(customerName = value) }

        fun setCustomerPhone(value: String) = _state.update { it.copy(customerPhone = value) }

        fun setStartDate(date: LocalDate) =
            _state.update {
                it.copy(startDate = date, endDate = maxOf(date, it.endDate))
            }

        fun setEndDate(date: LocalDate) = _state.update { it.copy(endDate = date) }

        fun setStartTime(time: LocalTime?) = _state.update { it.copy(startTime = time) }

        fun setEndTime(time: LocalTime?) = _state.update { it.copy(endTime = time) }

        fun setTotalAmount(text: String) = _state.update { it.copy(totalAmountText = text) }

        fun setSecurityDeposit(text: String) = _state.update { it.copy(securityDepositText = text) }

        fun setAdvance(text: String) = _state.update { it.copy(advanceText = text) }

        fun setSource(source: BookingSource?) = _state.update { it.copy(source = source) }

        fun setNotes(value: String) = _state.update { it.copy(notes = value) }

        fun dismissBlocker() = _state.update { it.copy(blocker = null) }

        // ---- save pipeline (§4.1): validate → blocked dates → conflict warning → persist ----

        fun save() {
            viewModelScope.launch { attemptSave(skipConflictWarning = false, overrideBlocks = false) }
        }

        /** Confirm button of the non-blocking conflict popup. */
        fun saveAnyway() {
            viewModelScope.launch { attemptSave(skipConflictWarning = true, overrideBlocks = false) }
        }

        /** Owner override on the blocked-dates popup. */
        fun saveDespiteBlock() {
            viewModelScope.launch { attemptSave(skipConflictWarning = false, overrideBlocks = true) }
        }

        private suspend fun attemptSave(
            skipConflictWarning: Boolean,
            overrideBlocks: Boolean,
        ) {
            val form = _state.value
            val business = businessRepository.businesses().first().firstOrNull { it.deletedAt == null } ?: return
            val currentActor = actor ?: actorProvider.actorFor(business).also { actor = it }

            if (form.customerName.isBlank()) {
                _state.update { it.copy(blocker = FormBlocker.NameRequired) }
                return
            }
            if (form.endDate.isBefore(form.startDate)) {
                _state.update { it.copy(blocker = FormBlocker.EndBeforeStart) }
                return
            }

            // Blocked dates DO block (with owner override) — checked before the soft warning.
            if (!overrideBlocks) {
                val blocks =
                    bookingRepository
                        .dateBlocksBetween(business.id, form.startDate, form.endDate)
                        .first()
                        .filter { it.deletedAt == null }
                if (blocks.isNotEmpty()) {
                    _state.update { it.copy(blocker = FormBlocker.BlockedDates(canOverride = currentActor.isOwner)) }
                    return
                }
            }

            // Non-blocking double-booking warning: halls can host multiple events (§4.1).
            if (!skipConflictWarning) {
                val conflicts = conflictCount(business.id, form)
                if (conflicts > 0) {
                    _state.update { it.copy(blocker = FormBlocker.Conflict(conflicts)) }
                    return
                }
            }

            persist(business.id, currentActor, form)
        }

        /** Max per-day count of OTHER live bookings across the selected range. */
        private suspend fun conflictCount(
            businessId: String,
            form: BookingFormState,
        ): Int {
            var maxCount = 0
            var date = form.startDate
            while (!date.isAfter(form.endDate)) {
                var count = bookingRepository.countBookingsOn(businessId, date)
                val edited = editing
                if (edited != null && date in edited.startDate..edited.endDate && edited.status != BookingStatus.CANCELLED) {
                    count -= 1 // don't count the booking being edited as its own conflict
                }
                maxCount = maxOf(maxCount, count)
                date = date.plusDays(1)
            }
            return maxCount
        }

        private suspend fun persist(
            businessId: String,
            currentActor: BookingActor,
            form: BookingFormState,
        ) {
            val allowed =
                currentActor.isOwner ||
                    (if (form.editingId == null) currentActor.permissions.create else currentActor.permissions.edit)
            if (!allowed) return

            val now = clock.instant()
            val type = form.eventType
            val isCustom = type == null || type.isCustom
            val eventTypeValue = if (isCustom) form.customLabel.ifBlank { EventType.CUSTOM_KEY } else type.key
            val eventIcon = if (isCustom) form.customEmoji.ifBlank { "✨" } else type.emoji

            val base = editing
            val booking =
                Booking(
                    id = base?.id ?: UUID.randomUUID().toString(),
                    businessId = businessId,
                    eventType = eventTypeValue,
                    eventIcon = eventIcon,
                    customerName = form.customerName.trim(),
                    customerPhone = form.customerPhone.trim().ifBlank { null },
                    startDate = form.startDate,
                    endDate = form.endDate,
                    startTime = form.startTime,
                    endTime = form.endTime,
                    totalAmountPaise = form.totalAmountPaise,
                    securityDepositPaise = form.securityDepositPaise,
                    source = form.source,
                    notes = form.notes.trim().ifBlank { null },
                    status = form.status,
                    gcalEventId = base?.gcalEventId,
                    invoiceNumber = base?.invoiceNumber,
                    createdBy = base?.createdBy ?: currentActor.userId,
                    updatedBy = if (base != null) currentActor.userId else null,
                    createdAt = base?.createdAt ?: now,
                    updatedAt = now,
                    deletedAt = null,
                )
            bookingRepository.saveBooking(booking)

            // The advance is simply the FIRST payment row, dated today (§2, §4.1).
            if (base == null && form.advancePaise > 0) {
                bookingRepository.recordPayment(
                    BookingPayment(
                        id = UUID.randomUUID().toString(),
                        bookingId = booking.id,
                        businessId = businessId,
                        amountPaise = form.advancePaise,
                        paidOn = LocalDate.now(clock),
                        method = PaymentMethod.CASH,
                        createdBy = currentActor.userId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }

            syncScheduler.requestImmediateSync()
            _state.update { it.copy(blocker = null, saved = true) }
        }
    }

private fun paiseToRupeeText(paise: Long): String =
    if (paise == 0L) {
        ""
    } else if (paise % 100 == 0L) {
        (paise / 100).toString()
    } else {
        java.math
            .BigDecimal(paise)
            .movePointLeft(2)
            .toPlainString()
    }
