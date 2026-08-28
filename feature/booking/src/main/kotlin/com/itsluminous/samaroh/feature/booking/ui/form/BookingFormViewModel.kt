package com.itsluminous.samaroh.feature.booking.ui.form

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.EventTypeRepository
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.core.model.PaymentMethod
import com.itsluminous.samaroh.core.model.ReminderKind
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.feature.booking.domain.BookingActor
import com.itsluminous.samaroh.feature.booking.domain.BookingActorProvider
import com.itsluminous.samaroh.feature.booking.domain.BuiltInEventType
import com.itsluminous.samaroh.feature.booking.domain.EventTypePresets
import com.itsluminous.samaroh.feature.booking.domain.TentativeFollowUpPlanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

    /** Manually entered invoice number already used by another booking (ADR-020). */
    data object DuplicateInvoiceNumber : FormBlocker
}

/**
 * The form's event-type selection (ADR-032): a DB-backed preset of the business, or the
 * always-available free-text Custom entry (label + emoji fields).
 */
sealed interface EventTypeChoice {
    data class Preset(
        val preset: EventType,
    ) : EventTypeChoice

    data object Custom : EventTypeChoice
}

data class BookingFormState(
    val loaded: Boolean = false,
    val editingId: String? = null,
    /** The business's live presets in sort order (ADR-032). */
    val presets: List<EventType> = emptyList(),
    val eventTypeChoice: EventTypeChoice? = null,
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
    /** Palette key from `shared/booking-colors.json` (ADR-030); null = Default. */
    val colorKey: String? = null,
    /** Which optional fields render (ADR-020, Settings → Booking form fields). */
    val fieldVisibility: BookingFormFieldVisibility = BookingFormFieldVisibility(),
    /** Manual invoice number as typed; editable only while [frozenInvoiceNumber] is null. */
    val invoiceNumberText: String = "",
    /** The already-assigned (immutable) invoice number of the booking being edited. */
    val frozenInvoiceNumber: String? = null,
    /** Tentative follow-up selector (§4.1 UX round): preset chip value. */
    val followUpDays: Int = TentativeFollowUpPlanner.DEFAULT_DAYS,
    /** Whether the "Custom" chip is active (day count comes from [followUpCustomText]). */
    val followUpCustom: Boolean = false,
    val followUpCustomText: String = "",
    val blocker: FormBlocker? = null,
    val saved: Boolean = false,
) {
    val totalAmountPaise: Long get() = parseRupeesToPaise(totalAmountText)
    val securityDepositPaise: Long get() = parseRupeesToPaise(securityDepositText)
    val advancePaise: Long get() = parseRupeesToPaise(advanceText)

    /** Live-updating, read-only due (§4.1): total − advance (edit mode shows card due instead). */
    val duePaise: Long get() = (totalAmountPaise - advancePaise).coerceAtLeast(0)

    /** The effective follow-up delay in days (custom text falls back to the default). */
    val effectiveFollowUpDays: Int
        get() =
            if (followUpCustom) {
                followUpCustomText.toIntOrNull()?.coerceIn(1, 365) ?: TentativeFollowUpPlanner.DEFAULT_DAYS
            } else {
                followUpDays
            }

    /**
     * Presets offered by the dropdown (ADR-032): a preset named "Custom" (any casing —
     * migration 006 / client seeding creates one) is REPRESENTED by the built-in
     * free-text Custom entry instead, so the picker never shows two Custom rows.
     */
    val pickerPresets: List<EventType>
        get() = presets.filterNot { EventTypePresets.normalize(it.label) == BuiltInEventType.CUSTOM_KEY }

    /** The label + icon a save would record right now (snapshot semantics, ADR-032). */
    val effectiveEventType: Pair<String, String>
        get() =
            when (val choice = eventTypeChoice) {
                is EventTypeChoice.Preset -> choice.preset.label to choice.preset.icon
                else -> customLabel.ifBlank { BuiltInEventType.CUSTOM_KEY } to customEmoji.ifBlank { "✨" }
            }

    /**
     * The current selection's type-default colour key (ADR-031 via presets, ADR-032) —
     * drives the picker's secondary "follows event type" ring.
     */
    val typeDefaultColorKey: String?
        get() = EventTypePresets.defaultColorKeyFor(presets, effectiveEventType.first)
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
        private val eventTypeRepository: EventTypeRepository,
        /** Booking colour palette (ADR-030), exposed for the form's picker row. */
        val bookingColorsProvider: BookingColorCatalog,
        private val syncScheduler: SyncScheduler,
        fieldPrefs: BookingFormFieldPrefs,
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
            // Optional-field visibility (ADR-020): live so a Settings change mid-session applies.
            viewModelScope.launch {
                fieldPrefs.visibility.collect { visibility ->
                    _state.update { it.copy(fieldVisibility = visibility) }
                }
            }
            viewModelScope.launch {
                val business = businessRepository.businesses().first().firstOrNull { it.deletedAt == null }
                actor = business?.let { actorProvider.actorFor(it) }
                val existing = editBookingId?.let { bookingRepository.booking(it) }
                editing = existing
                // Live presets (ADR-032): the FIRST emission also initializes the
                // selection; later emissions (a preset added/renamed mid-session) only
                // refresh the list — the current selection snapshot stays.
                var initialized = false
                val presetFlow =
                    if (business == null) flowOf(emptyList()) else eventTypeRepository.presets(business.id)
                presetFlow.collect { types ->
                    if (initialized) {
                        _state.update { it.copy(presets = types) }
                        return@collect
                    }
                    initialized = true
                    _state.update { state ->
                        if (existing == null) {
                            state.copy(
                                loaded = true,
                                presets = types,
                                eventTypeChoice = defaultChoice(types),
                            )
                        } else {
                            val match = matchingPreset(types, existing.eventType)
                            state.copy(
                                loaded = true,
                                editingId = existing.id,
                                presets = types,
                                eventTypeChoice = match?.let { EventTypeChoice.Preset(it) } ?: EventTypeChoice.Custom,
                                customLabel =
                                    if (match == null && existing.eventType != BuiltInEventType.CUSTOM_KEY) existing.eventType else "",
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
                                colorKey = existing.color,
                                frozenInvoiceNumber = existing.invoiceNumber,
                            )
                        }
                    }
                }
            }
        }

        /** New-booking default: the Wedding preset if present, else the first preset, else Custom. */
        private fun defaultChoice(types: List<EventType>): EventTypeChoice {
            val picker = types.filterNot { EventTypePresets.normalize(it.label) == BuiltInEventType.CUSTOM_KEY }
            val wedding = picker.firstOrNull { EventTypePresets.normalize(it.label) == "wedding" }
            return (wedding ?: picker.firstOrNull())?.let { EventTypeChoice.Preset(it) } ?: EventTypeChoice.Custom
        }

        /**
         * The preset a stored `event_type` corresponds to, by NORMALIZED label — legacy
         * built-in keys (`room_booking`) match their seeded preset (`Room Booking`).
         * A "Custom"-named preset never matches: those bookings edit as free text.
         */
        private fun matchingPreset(
            types: List<EventType>,
            eventType: String,
        ): EventType? {
            val wanted = EventTypePresets.normalize(eventType)
            if (wanted == BuiltInEventType.CUSTOM_KEY) return null
            return types.firstOrNull { EventTypePresets.normalize(it.label) == wanted }
        }

        // ---- field updates ----

        fun setEventType(choice: EventTypeChoice) = _state.update { it.copy(eventTypeChoice = choice) }

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

        /** Booking colour (ADR-030): palette key, or null for the Default themed look. */
        fun setColor(colorKey: String?) = _state.update { it.copy(colorKey = colorKey) }

        fun setNotes(value: String) = _state.update { it.copy(notes = value) }

        /** Manual invoice number — only meaningful while no number is frozen (ADR-020). */
        fun setInvoiceNumber(value: String) = _state.update { it.copy(invoiceNumberText = value) }

        /** Tentative follow-up: preset chip (1/3/7 days). */
        fun selectFollowUpPreset(days: Int) = _state.update { it.copy(followUpDays = days, followUpCustom = false) }

        /** Tentative follow-up: "Custom" chip. */
        fun selectFollowUpCustom() = _state.update { it.copy(followUpCustom = true) }

        fun setFollowUpCustomText(value: String) = _state.update { it.copy(followUpCustomText = value.filter(Char::isDigit).take(3)) }

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

            // Manual invoice number (ADR-020): unique per business while still unfrozen.
            val manualInvoice = form.invoiceNumberText.trim()
            if (form.frozenInvoiceNumber == null &&
                manualInvoice.isNotEmpty() &&
                bookingRepository.invoiceNumberExists(business.id, manualInvoice, form.editingId)
            ) {
                _state.update { it.copy(blocker = FormBlocker.DuplicateInvoiceNumber) }
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
            // Snapshot semantics (ADR-032): the preset's CURRENT label + icon are
            // recorded on the booking; later preset edits never rewrite it.
            val (eventTypeValue, eventIcon) = form.effectiveEventType

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
                    color = form.colorKey,
                    gcalEventId = base?.gcalEventId,
                    // Frozen once set (manually or by the allocator, ADR-006/ADR-020);
                    // otherwise an optional manual number, validated unique in attemptSave.
                    invoiceNumber = base?.invoiceNumber ?: form.invoiceNumberText.trim().ifBlank { null },
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

            reconcileFollowUp(booking, form)

            syncScheduler.requestImmediateSync()
            _state.update { it.copy(blocker = null, saved = true) }
        }

        /**
         * Tentative follow-up loop (ADR-020): saving as Tentative supersedes any pending
         * follow-up with one at `today + N`; saving with any other status dismisses them
         * (the icon and reminders revert once the booking is confirmed/cancelled).
         */
        private suspend fun reconcileFollowUp(
            booking: Booking,
            form: BookingFormState,
        ) {
            val now = clock.instant()
            val pendingFollowUps =
                bookingRepository
                    .remindersForBooking(booking.id)
                    .filter { it.kind == ReminderKind.FOLLOW_UP && it.status == ReminderStatus.PENDING }
            pendingFollowUps.forEach {
                bookingRepository.saveReminder(it.copy(status = ReminderStatus.DISMISSED, updatedAt = now))
            }
            if (form.status == BookingStatus.TENTATIVE) {
                bookingRepository.saveReminder(
                    TentativeFollowUpPlanner.create(
                        booking = booking,
                        daysFromNow = form.effectiveFollowUpDays,
                        today = LocalDate.now(clock),
                        newId = { UUID.randomUUID().toString() },
                        now = now,
                    ),
                )
            }
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
