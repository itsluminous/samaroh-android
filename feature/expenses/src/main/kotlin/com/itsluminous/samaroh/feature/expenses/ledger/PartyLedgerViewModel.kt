package com.itsluminous.samaroh.feature.expenses.ledger

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.navigation.BookingCardOpener
import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkException
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.i18n.EventTypeLabels
import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.feature.expenses.ExpensesSession
import com.itsluminous.samaroh.feature.expenses.attachments.AttachmentContentResolver
import com.itsluminous.samaroh.feature.expenses.attachments.AttachmentDeleter
import com.itsluminous.samaroh.feature.expenses.attachments.AttachmentOpenResult
import com.itsluminous.samaroh.feature.expenses.domain.FuzzyNameMatcher
import com.itsluminous.samaroh.feature.expenses.domain.LedgerRow
import com.itsluminous.samaroh.feature.expenses.domain.RunningBalanceCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import javax.inject.Inject

/** Route argument name for the party id (shared by ledger and add-entry destinations). */
const val ARG_PARTY_ID = "partyId"

/**
 * The event an entry was recorded against (ADR-101 §3), rendered as a small chip
 * `Event: {label}`. [bookingId]/[businessId] are null when the booking is not resolvable
 * locally (tombstoned / not pulled yet) and the label came from the notes fallback —
 * such a chip is not tappable.
 */
data class EventChip(
    /** The booking customer's first name (`BookingTitleFormatter.firstName` convention). */
    val customerFirstName: String? = null,
    /** The stored `bookings.event_type`; the UI localizes it (`EventTypeLabels`). */
    val eventType: String? = null,
    /** Notes-derived label when the booking is unresolvable: the first `·` segment of the notes. */
    val fallbackLabel: String? = null,
    val bookingId: String? = null,
    val businessId: String? = null,
) {
    /** Tappable only when the booking resolved locally (ADR-101 §3). */
    val resolvable: Boolean get() = bookingId != null && businessId != null

    companion object {
        /** `{eventType} · {dates}` / `{first name} · {event type}` separator (shared contract §1/§3). */
        const val SEPARATOR = " · "

        /**
         * Fallback for an unresolvable booking: the notes' first segment IF they still have
         * the `booking_notes_default` shape (`{eventType} · {dates}`), else null (no chip).
         */
        fun fallbackLabel(notes: String?): String? {
            val text = notes?.trim().orEmpty()
            if (!text.contains(SEPARATOR)) return null
            return text.substringBefore(SEPARATOR).trim().ifEmpty { null }
        }
    }
}

data class PartyLedgerState(
    val party: Party? = null,
    /** Newest-first entries annotated with the balance after each (§4.2). */
    val rows: List<LedgerRow> = emptyList(),
    /** expenseId → its live attachments (thumbnails + pending badges). */
    val attachmentsByExpense: Map<String, List<AttachmentWithLocalState>> = emptyMap(),
    /** expenseId → the event chip of entries recorded against a booking (ADR-101). */
    val eventChips: Map<String, EventChip> = emptyMap(),
    /** `booking.view` (ADR-101 §3): the chip opens the booking card only when true; shown regardless. */
    val canOpenBookings: Boolean = true,
    /** Net balance = the newest row's balance-after (0 when no entries). */
    val netBalancePaise: Long = 0,
    /** Drives the PermissionGate around edit/delete (`expenses.edit`, owner-mode default). */
    val canEditEntries: Boolean = true,
    /** `expenses.create` gate: shows the You gave / You got buttons. */
    val canCreateEntries: Boolean = true,
    /** Entry-delete gate: `expenses.delete`. */
    val canDeleteEntries: Boolean = true,
    /** Party-edit gate (ADR-028): `expenses.edit` OR `expenses.manage_parties`. */
    val canEditParty: Boolean = true,
    /** Party-delete gate (ADR-028): `expenses.delete`. */
    val canDeleteParty: Boolean = true,
    /** ADR-039 gate: `expenses.view_amounts`; masks entry amounts and balances as ₹••• when false. */
    val canViewAmounts: Boolean = true,
    /** Active business display name for the edit-party "Associated with {business}?" pill. */
    val businessName: String = "",
    /**
     * Google is configured but the signed-in user has no linked account — pending
     * attachment badges point at the Settings link flow instead of a bare "waiting".
     */
    val googleUnlinked: Boolean = false,
    val loaded: Boolean = false,
)

/** Validation outcome of an edit-party save attempt (ADR-028). */
enum class EditPartyError {
    /** Trimmed name is empty. */
    EMPTY_NAME,

    /** Another live party of the business already carries this (normalized) name. */
    DUPLICATE_NAME,
}

sealed interface PartyLedgerEvent {
    /** The edit-party dialog saved successfully — close it. */
    data object PartySaved : PartyLedgerEvent

    /** The party (and its cascade) was deleted — toast + navigate back to the list. */
    data class PartyDeleted(
        val partyName: String,
    ) : PartyLedgerEvent

    /** A tapped attachment resolved to a local file — image → in-app viewer, else ACTION_VIEW (ADR-052). */
    data class OpenAttachment(
        val file: File,
        val mimeType: String,
        /** The tapped row — the viewer's download/delete actions need its metadata (ADR-053). */
        val attachment: AttachmentWithLocalState,
    ) : PartyLedgerEvent

    /** A bill image was deleted from the viewer — the screen closes it and confirms. */
    data object AttachmentDeleted : PartyLedgerEvent

    /** Tapped attachment has neither a local file nor a Drive copy yet (pending on another device). */
    data object AttachmentUnavailable : PartyLedgerEvent

    /** Drive download of a tapped attachment failed (typically offline) — friendly retry message. */
    data object AttachmentDownloadFailed : PartyLedgerEvent

    /** The link-Google flow launched from the view-attachment dialog failed (not a cancel). */
    data object GoogleLinkFailed : PartyLedgerEvent
}

@HiltViewModel
class PartyLedgerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val expensesRepository: ExpensesRepository,
        private val ledgerRepository: ExpensesLedgerRepository,
        private val session: ExpensesSession,
        private val googleAccountLinker: GoogleAccountLinker,
        private val attachmentResolver: AttachmentContentResolver,
        private val attachmentDeleter: AttachmentDeleter,
        private val syncScheduler: SyncScheduler,
        private val bookingRepository: BookingRepository,
        private val bookingCardOpener: BookingCardOpener,
        private val clock: Clock,
    ) : ViewModel() {
        val partyId: String = checkNotNull(savedStateHandle[ARG_PARTY_ID])

        /** Bumped after a party edit so the one-shot party lookup re-emits the fresh row. */
        private val partyRefresh = MutableStateFlow(0)

        private val _editPartyError = MutableStateFlow<EditPartyError?>(null)

        /** Validation error of the last edit-party save attempt; cleared on retry/dismiss. */
        val editPartyError: StateFlow<EditPartyError?> = _editPartyError.asStateFlow()

        private val _events = MutableSharedFlow<PartyLedgerEvent>(extraBufferCapacity = 1)
        val events: SharedFlow<PartyLedgerEvent> = _events.asSharedFlow()

        /** All permission gates + Google link state as one flow (keeps the state combine at 5 sources). */
        private val gates =
            combine(
                combine(
                    session.canEditEntries,
                    session.canCreateEntries,
                    session.canDeleteEntries,
                    session.canManageParties,
                    session.canDeleteParties,
                ) { canEdit, canCreate, canDeleteEntry, canManage, canDeleteParty ->
                    Gates(canEdit, canCreate, canDeleteEntry, canManage, canDeleteParty)
                },
                session.canViewAmounts,
                googleAccountLinker.linkState,
                session.canViewBookings,
            ) { base, viewAmounts, link, viewBookings ->
                base.copy(viewAmounts = viewAmounts, googleUnlinked = link is GoogleLinkState.NotLinked, viewBookings = viewBookings)
            }

        private val entries = expensesRepository.entriesForParty(partyId)

        /**
         * Event chips (ADR-101 §3), resolved per distinct `booking_id` whenever the entries
         * change: live booking → `{first name} · {event type}`; unresolvable (tombstoned, not
         * pulled) → the notes' first segment when they still have the default shape, else no
         * chip. Never a bare id, never a crash.
         */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val eventChips: kotlinx.coroutines.flow.Flow<Map<String, EventChip>> =
            entries.flatMapLatest { list -> flow { emit(resolveEventChips(list)) } }

        val state: StateFlow<PartyLedgerState> =
            combine(
                partyRefresh.map { ledgerRepository.party(partyId) },
                combine(entries, eventChips) { list, chips -> list to chips },
                ledgerRepository.attachmentsForParty(partyId),
                gates,
                session.businessName,
            ) { party, (entries, chips), attachments, gate, businessName ->
                val rows = RunningBalanceCalculator.withRunningBalance(entries)
                PartyLedgerState(
                    party = party,
                    rows = rows,
                    attachmentsByExpense = attachments.groupBy { it.attachment.expenseId },
                    eventChips = chips,
                    canOpenBookings = gate.viewBookings,
                    netBalancePaise = rows.firstOrNull()?.balanceAfterPaise ?: 0,
                    canEditEntries = gate.editEntries,
                    canCreateEntries = gate.createEntries,
                    canDeleteEntries = gate.deleteEntries,
                    canEditParty = gate.manageParties,
                    canDeleteParty = gate.deleteParties,
                    canViewAmounts = gate.viewAmounts,
                    businessName = businessName,
                    googleUnlinked = gate.googleUnlinked,
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PartyLedgerState())

        /** The §3 gates the ledger screen renders from (+ Google link state, same combine). */
        private data class Gates(
            val editEntries: Boolean,
            val createEntries: Boolean,
            val deleteEntries: Boolean,
            val manageParties: Boolean,
            val deleteParties: Boolean,
            val viewAmounts: Boolean = true,
            val googleUnlinked: Boolean = false,
            val viewBookings: Boolean = true,
        )

        private suspend fun resolveEventChips(entries: List<Expense>): Map<String, EventChip> {
            val linked = entries.filter { it.bookingId != null }
            if (linked.isEmpty()) return emptyMap()
            val bookings =
                linked
                    .mapNotNull { it.bookingId }
                    .distinct()
                    .associateWith { id -> runCatching { bookingRepository.booking(id) }.getOrNull() }
            return buildMap {
                for (expense in linked) {
                    val booking = bookings[expense.bookingId]?.takeIf { it.deletedAt == null }
                    val chip =
                        if (booking != null) {
                            EventChip(
                                customerFirstName = EventTypeLabels.customerFirstName(booking.customerName),
                                eventType = booking.eventType,
                                bookingId = booking.id,
                                businessId = booking.businessId,
                            )
                        } else {
                            EventChip.fallbackLabel(expense.notes)?.let { EventChip(fallbackLabel = it) }
                        }
                    if (chip != null) put(expense.id, chip)
                }
            }
        }

        /** Open the chip's booking card (ADR-098 route) — only for resolvable bookings and `booking.view` holders. */
        fun openBooking(chip: EventChip) {
            val bookingId = chip.bookingId ?: return
            val businessId = chip.businessId ?: return
            if (!state.value.canOpenBookings) return
            bookingCardOpener.openBookingCard(bookingId, businessId)
        }

        /**
         * Tombstone delete (§4.2), cascading to the entry's attachments (ADR-063): the
         * rows disappear locally, each delete syncs as a tombstone, local cache files
         * are removed and Drive copies are best-effort deleted (non-fatal, ADR-053
         * semantics) — a deleted bill no longer lingers in the uploader's Drive.
         */
        fun deleteEntry(expenseId: String) {
            viewModelScope.launch {
                val deletedAttachments = ledgerRepository.deleteExpenseCascade(expenseId)
                attachmentDeleter.cleanUpCascade(deletedAttachments)
            }
        }

        // ---- View attachment (ADR-052) -------------------------------------------------

        /** Attachment id currently being resolved — drives the thumbnail's loading spinner. */
        private val _openingAttachmentId = MutableStateFlow<String?>(null)
        val openingAttachmentId: StateFlow<String?> = _openingAttachmentId.asStateFlow()

        /** Attachment waiting on a Google link; non-null shows the link-to-view dialog. */
        private val attachmentAwaitingLink = MutableStateFlow<AttachmentWithLocalState?>(null)
        val showAttachmentLinkPrompt: StateFlow<Boolean> =
            attachmentAwaitingLink
                .map { it != null }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        /** The link-to-view dialog's Connect button is running the account-picker flow. */
        private val _attachmentLinking = MutableStateFlow(false)
        val attachmentLinking: StateFlow<Boolean> = _attachmentLinking.asStateFlow()

        /** Pending Google scope-consent sheet the screen must launch, then call [completeGoogleConsent]. */
        private val _consentIntent = MutableStateFlow<PendingIntent?>(null)
        val consentIntent: StateFlow<PendingIntent?> = _consentIntent.asStateFlow()

        /**
         * Opens a tapped attachment (ADR-052): local cache → immediately; else Drive
         * download (spinner on the thumbnail) → open; else the link dialog / the localized
         * "not on this device yet" / offline messages. One resolution at a time — a second
         * tap while a download runs is ignored rather than queued.
         */
        fun openAttachment(attachment: AttachmentWithLocalState) {
            if (_openingAttachmentId.value != null) return
            _openingAttachmentId.value = attachment.attachment.id
            viewModelScope.launch {
                when (val result = attachmentResolver.resolve(attachment)) {
                    is AttachmentOpenResult.Ready ->
                        _events.emit(PartyLedgerEvent.OpenAttachment(result.file, result.mimeType, attachment))
                    AttachmentOpenResult.NeedsGoogleLink -> attachmentAwaitingLink.value = attachment
                    AttachmentOpenResult.NotAvailable -> _events.emit(PartyLedgerEvent.AttachmentUnavailable)
                    AttachmentOpenResult.DownloadFailed -> _events.emit(PartyLedgerEvent.AttachmentDownloadFailed)
                }
                _openingAttachmentId.value = null
            }
        }

        /**
         * Confirmed viewer delete (ADR-053): tombstone + outbox via the repository, local
         * cache file removed, best-effort Drive `files.delete` — the [AttachmentDeleter]
         * owns the cascade. The ledger's Room flow drops the thumbnail reactively.
         */
        fun deleteAttachment(attachment: AttachmentWithLocalState) {
            viewModelScope.launch {
                attachmentDeleter.delete(attachment)
                _events.emit(PartyLedgerEvent.AttachmentDeleted)
            }
        }

        /** "Later" on the link-to-view dialog — nothing opens; the thumbnail stays tappable. */
        fun dismissAttachmentLinkPrompt() {
            if (_attachmentLinking.value) return
            attachmentAwaitingLink.value = null
        }

        /**
         * "Connect Google" on the link-to-view dialog: runs the Settings link flow in place
         * (§4.4 linker; same plumbing as the add-entry prompt). [activityContext] MUST be an
         * Activity context — Credential Manager shows UI. On success the tapped attachment
         * is re-resolved, which now downloads it from Drive.
         */
        fun linkGoogleToView(activityContext: Context) {
            if (_attachmentLinking.value) return
            _attachmentLinking.value = true
            viewModelScope.launch {
                googleAccountLinker
                    .link(activityContext)
                    .onSuccess { onLinkedForView() }
                    .onFailure(::handleLinkFailure)
            }
        }

        /** Completes the link after the scope-consent sheet returned [resultIntent]. */
        fun completeGoogleConsent(resultIntent: Intent?) {
            _consentIntent.value = null
            viewModelScope.launch {
                googleAccountLinker
                    .completeLink(resultIntent)
                    .onSuccess { onLinkedForView() }
                    .onFailure(::handleLinkFailure)
            }
        }

        private fun onLinkedForView() {
            // Freshly linked: nudge sync so THIS device's pending uploads move too, then
            // retry the tapped attachment — the resolver now takes the download path.
            syncScheduler.requestImmediateSync()
            val pending = attachmentAwaitingLink.value
            attachmentAwaitingLink.value = null
            _attachmentLinking.value = false
            pending?.let(::openAttachment)
        }

        private fun handleLinkFailure(error: Throwable) {
            when (error) {
                is GoogleLinkException.NeedsScopeConsent -> {
                    _consentIntent.value = error.pendingIntent
                    return // keep `linking`; the consent sheet continues the flow
                }
                is GoogleLinkException.Cancelled -> Unit // keep the dialog; Later still works
                else -> _events.tryEmit(PartyLedgerEvent.GoogleLinkFailed)
            }
            _attachmentLinking.value = false
        }

        /**
         * Edit-party save (ADR-028): name (trimmed, deduped against the business's other
         * live parties), optional phone and the business/personal flag — full parity with
         * the add-person form. Emits [PartyLedgerEvent.PartySaved] on success; sets
         * [editPartyError] and keeps the dialog open otherwise.
         */
        fun saveParty(
            name: String,
            phone: String,
            businessRelated: Boolean,
        ) {
            val party = state.value.party ?: return
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                _editPartyError.value = EditPartyError.EMPTY_NAME
                return
            }
            viewModelScope.launch {
                val normalized = FuzzyNameMatcher.normalize(trimmed)
                val duplicate =
                    expensesRepository
                        .partiesWithBalance(session.businessId())
                        .first()
                        .any { it.party.id != party.id && FuzzyNameMatcher.normalize(it.party.name) == normalized }
                if (duplicate) {
                    _editPartyError.value = EditPartyError.DUPLICATE_NAME
                    return@launch
                }
                _editPartyError.value = null
                val updated =
                    party.copy(
                        name = trimmed,
                        phone = phone.trim().ifEmpty { null },
                        businessRelated = businessRelated,
                        updatedAt = clock.instant(),
                    )
                if (updated != party) expensesRepository.saveParty(updated)
                partyRefresh.update { it + 1 }
                _events.emit(PartyLedgerEvent.PartySaved)
            }
        }

        /** Clears the edit validation error (dialog dismissed or the name was retyped). */
        fun clearEditPartyError() {
            _editPartyError.value = null
        }

        /**
         * Delete party (ADR-028, Drive cleanup added by ADR-063): cascade-tombstones the
         * party, its expenses and their attachments (one outbox DELETE per row), removes
         * the local cached attachment files, best-effort deletes the Drive copies, then
         * emits [PartyLedgerEvent.PartyDeleted] so the UI navigates back.
         */
        fun deleteParty() {
            val party = state.value.party ?: return
            viewModelScope.launch {
                val deletedAttachments = ledgerRepository.deletePartyCascade(party.id)
                attachmentDeleter.cleanUpCascade(deletedAttachments)
                _events.emit(PartyLedgerEvent.PartyDeleted(party.name))
            }
        }
    }
