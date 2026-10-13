package com.itsluminous.samaroh.feature.expenses.addentry

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.attachments.AttachmentUploadQueue
import com.itsluminous.samaroh.core.data.navigation.BookingExpenseDraft
import com.itsluminous.samaroh.core.data.navigation.BookingExpenseDraftHolder
import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkException
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.ExpenseAttachment
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.feature.expenses.ExpensesSession
import com.itsluminous.samaroh.feature.expenses.attachments.AttachmentCompressor
import com.itsluminous.samaroh.feature.expenses.attachments.AttachmentDeleter
import com.itsluminous.samaroh.feature.expenses.domain.AmountInput
import com.itsluminous.samaroh.feature.expenses.ledger.ARG_PARTY_ID
import com.itsluminous.samaroh.feature.expenses.sharetarget.ShareTargetHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** Route argument: entry direction ('paid' = you gave, 'received' = you got). */
const val ARG_DIRECTION = "direction"

/** Optional route argument: id of an existing entry to edit (§4.2 edit, reuses this screen). */
const val ARG_EXPENSE_ID = "expenseId"

/** Optional route argument (ADR-078): pre-attach the Create-invoice shared file. */
const val ARG_FROM_SHARE = "fromShare"

/**
 * Route argument of the record-expense-from-booking destination (ADR-101): the booking
 * the pending [BookingExpenseDraft] is for. The route has NO party — see [AddEntryViewModel].
 */
const val ARG_BOOKING_ID = "bookingId"

/** Saved-state key: the consumed [BookingExpenseDraft] (JSON) so recreation keeps the pre-fill. */
private const val STATE_BOOKING_DRAFT = "bookingDraft"

/** Saved-state key: the default notes were applied once (a recreated form keeps the user's text). */
private const val STATE_NOTES_SEEDED = "bookingNotesSeeded"

/** Attachments per entry are capped at 4 (§4.2). */
const val MAX_ATTACHMENTS = 4

/** A compressed local file staged for saving with the entry. */
data class StagedAttachment(
    val file: File,
    val mimeType: String,
    val fileName: String,
)

data class AddEntryState(
    val direction: ExpenseDirection = ExpenseDirection.PAID,
    val amountText: String = "",
    val amountError: Boolean = false,
    val date: LocalDate,
    val notes: String = "",
    /**
     * Edit mode (ADR-084): the entry's live attachments as of opening, with the ones the
     * user removed already filtered out. Removal is DEFERRED to Save — exactly like the
     * web entry dialog's chip-X — so Back/cancel leaves the bills untouched.
     */
    val existingAttachments: List<AttachmentWithLocalState> = emptyList(),
    val attachments: List<StagedAttachment> = emptyList(),
    val saving: Boolean = false,
    /** Set after save when attachments exist but no Google account is linked (§4.2 prompt). */
    val showGooglePrompt: Boolean = false,
    /** The prompt's Link button is running the account-picker/consent flow. */
    val linking: Boolean = false,
    /**
     * Record-expense-from-booking mode (ADR-106 §2): the EVENT PARTY's name
     * (`{EventTypeLabel} - {dd-MMM-yyyy} - {CustomerName}`, English) the entry will be
     * resolved to (or created as) on save — shown read-only since the route has no party.
     */
    val bookingPartyName: String? = null,
    /**
     * The booking-draft route was reached with NO draft to show (stale back stack after the
     * holder was emptied elsewhere): the form cannot save anything — the screen leaves.
     */
    val unusable: Boolean = false,
) {
    /** Bills on the entry after Save: kept existing ones plus newly staged (the §4.2 cap counts both). */
    val attachmentCount: Int get() = existingAttachments.size + attachments.size
}

sealed interface AddEntryEvent {
    /** Saved; [partyId] is the entry's party (resolved/created on save in booking mode). */
    data class Saved(
        val partyId: String,
    ) : AddEntryEvent

    data object AttachmentLimitReached : AddEntryEvent

    data object AttachmentFailed : AddEntryEvent

    /** The picked document exceeds the attachment size cap (distinct, actionable message). */
    data object AttachmentTooLarge : AddEntryEvent

    /** The link-Google flow launched from the prompt failed (not a cancel). */
    data object GoogleLinkFailed : AddEntryEvent

    /**
     * ADR-106 §2.2 step 3: the booking has no event party yet and this member lacks
     * `expenses.manage_parties` — nothing was written; the screen shows the blocking message
     * with the event party's name.
     */
    data class PartyCreateForbidden(
        val partyName: String,
    ) : AddEntryEvent
}

@HiltViewModel
class AddEntryViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val expensesRepository: ExpensesRepository,
        private val ledgerRepository: ExpensesLedgerRepository,
        private val uploadQueue: AttachmentUploadQueue,
        private val compressor: AttachmentCompressor,
        private val attachmentDeleter: AttachmentDeleter,
        private val session: ExpensesSession,
        private val googleAccountLinker: GoogleAccountLinker,
        private val syncScheduler: SyncScheduler,
        private val shareTargetHolder: ShareTargetHolder,
        private val bookingExpenseDrafts: BookingExpenseDraftHolder,
        private val clock: Clock,
    ) : ViewModel() {
        /**
         * Record-expense-from-booking pre-fill (ADR-101/ADR-106). Taken from the holder on first
         * creation (and consumed there), then kept in saved state so recreation does not
         * depend on the holder. Null on the ordinary party routes.
         */
        private val bookingDraft: BookingExpenseDraft? =
            savedStateHandle.get<String>(ARG_BOOKING_ID)?.let { bookingId ->
                savedStateHandle.get<String>(STATE_BOOKING_DRAFT)?.let { Json.decodeFromString<BookingExpenseDraft>(it) }
                    ?: bookingExpenseDrafts.pending.value
                        ?.takeIf { it.bookingId == bookingId }
                        ?.also { draft ->
                            savedStateHandle[STATE_BOOKING_DRAFT] = Json.encodeToString(BookingExpenseDraft.serializer(), draft)
                            bookingExpenseDrafts.consume(draft)
                        }
            }

        /** The route's party, or null in booking mode (the party is resolved on save). */
        private val routePartyId: String? = savedStateHandle[ARG_PARTY_ID]

        /** Party of the entry being EDITED / added to; booking mode has none until save. */
        private val partyId: String
            get() = checkNotNull(routePartyId) { "party route argument" }

        /** Booking mode defaults to "You gave" (contract §1); the party routes carry it. */
        private val direction =
            savedStateHandle.get<String>(ARG_DIRECTION)?.let(ExpenseDirection::fromWire) ?: ExpenseDirection.PAID

        /** Non-null when editing an existing entry (gated by `expenses.edit`, §4.2). */
        private val editingExpenseId: String? = savedStateHandle.get<String>(ARG_EXPENSE_ID)?.ifEmpty { null }
        private var editingExpense: Expense? = null

        /** Existing attachments the user removed in this edit session; tombstoned on Save (ADR-084). */
        private val removedAttachments = mutableListOf<AttachmentWithLocalState>()

        /** Party the saved entry belongs to (booking mode resolves it on save); for the deferred Saved events. */
        private var savedPartyId: String? = null

        private val _state =
            MutableStateFlow(
                AddEntryState(
                    direction = direction,
                    date = LocalDate.now(clock.withZone(ZoneId.systemDefault())),
                    // Booking mode: notes pre-filled by the Booking side ("{eventType} · {dates}",
                    // already localized); the user may edit or clear them. Only on FIRST
                    // creation — a recreated ViewModel restores whatever the user typed.
                    notes = bookingDraft?.defaultNotes.takeIf { savedStateHandle.get<Boolean>(STATE_NOTES_SEEDED) != true }.orEmpty(),
                    bookingPartyName = bookingDraft?.eventPartyName,
                    unusable = routePartyId == null && bookingDraft == null,
                ),
            )
        val state: StateFlow<AddEntryState> = _state.asStateFlow()

        init {
            if (bookingDraft != null) savedStateHandle[STATE_NOTES_SEEDED] = true
            editingExpenseId?.let { id ->
                viewModelScope.launch {
                    val existing = expensesRepository.entriesForParty(partyId).first().find { it.id == id } ?: return@launch
                    editingExpense = existing
                    _state.update {
                        it.copy(
                            direction = existing.direction,
                            amountText = BigDecimal(existing.amountPaise).movePointLeft(2).toPlainString(),
                            date = existing.expenseDate,
                            notes = existing.notes.orEmpty(),
                            // Snapshot (not a live flow): a sync pull mid-edit must not
                            // resurrect a chip the user already removed from the form.
                            existingAttachments = ledgerRepository.attachmentsForExpense(id).first(),
                        )
                    }
                }
            }
            // Create-invoice share target (ADR-078): the shared image/PDF pre-attaches
            // through the EXACT picked-attachment pipeline (compression, staging, Drive
            // upload on save). One-shot: consuming clears the holder so a later plain
            // add-entry never re-attaches a stale share.
            if (savedStateHandle.get<Boolean>(ARG_FROM_SHARE) == true) {
                shareTargetHolder.consume()?.let { shared ->
                    onAttachmentPicked(shared.uri, shared.mimeType, shared.displayName)
                }
            }
        }

        private val _events = MutableSharedFlow<AddEntryEvent>(extraBufferCapacity = 1)
        val events: SharedFlow<AddEntryEvent> = _events.asSharedFlow()

        /**
         * Live Google link state (§4.2): the post-save prompt shows only for
         * [GoogleLinkState.NotLinked] — when Google is not configured at all, linking is
         * impossible, so the flow just finishes (attachments stay visibly pending).
         * `null` until the first emission; treated as "don't prompt" to never block a save.
         */
        private val linkState: StateFlow<GoogleLinkState?> =
            googleAccountLinker.linkState.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** Pending Google scope-consent sheet the screen must launch, then call [completeGoogleConsent]. */
        private val _consentIntent = MutableStateFlow<PendingIntent?>(null)
        val consentIntent: StateFlow<PendingIntent?> = _consentIntent.asStateFlow()

        fun onAmountChange(text: String) {
            _state.update { it.copy(amountText = text, amountError = false) }
        }

        fun onDateChange(date: LocalDate) {
            _state.update { it.copy(date = date) }
        }

        fun onNotesChange(notes: String) {
            _state.update { it.copy(notes = notes) }
        }

        /** Stages a picked image/PDF: light compression for images, PDFs untouched (§4.2). */
        fun onAttachmentPicked(
            uri: Uri,
            mimeType: String,
            displayName: String,
        ) {
            if (_state.value.attachmentCount >= MAX_ATTACHMENTS) {
                _events.tryEmit(AddEntryEvent.AttachmentLimitReached)
                return
            }
            viewModelScope.launch {
                acceptPrepared(compressor.prepare(uri, mimeType, displayName))
            }
        }

        /** Stages a camera capture written to [file] by the TakePicture contract. */
        fun onImageCaptured(file: File) {
            if (_state.value.attachmentCount >= MAX_ATTACHMENTS) {
                _events.tryEmit(AddEntryEvent.AttachmentLimitReached)
                return
            }
            viewModelScope.launch {
                val result = compressor.prepareCapturedImage(file)
                file.delete() // raw capture superseded by the compressed copy
                acceptPrepared(result)
            }
        }

        /** Prepare verdicts become staged chips or distinct inline errors (ADR-050). */
        private suspend fun acceptPrepared(result: AttachmentCompressor.PrepareResult) {
            when (result) {
                is AttachmentCompressor.PrepareResult.Ready -> stage(result.prepared)
                AttachmentCompressor.PrepareResult.TooLarge -> _events.emit(AddEntryEvent.AttachmentTooLarge)
                AttachmentCompressor.PrepareResult.Unreadable -> _events.emit(AddEntryEvent.AttachmentFailed)
            }
        }

        fun removeAttachment(attachment: StagedAttachment) {
            attachment.file.delete()
            _state.update { current -> current.copy(attachments = current.attachments - attachment) }
        }

        /**
         * Removes an ALREADY-SAVED attachment from the form (edit mode, ADR-084). Only the
         * form state changes here; the tombstone + Drive cleanup run on [save], mirroring
         * the web dialog (chip-X collects ids, Save applies them, Cancel discards).
         */
        fun removeExistingAttachment(attachment: AttachmentWithLocalState) {
            val id = attachment.attachment.id
            if (removedAttachments.none { it.attachment.id == id }) removedAttachments += attachment
            _state.update { current ->
                current.copy(existingAttachments = current.existingAttachments.filterNot { it.attachment.id == id })
            }
        }

        private fun stage(prepared: AttachmentCompressor.Prepared) {
            _state.update { current ->
                current.copy(
                    attachments =
                        current.attachments +
                            StagedAttachment(file = prepared.file, mimeType = prepared.mimeType, fileName = prepared.fileName),
                )
            }
        }

        fun save() {
            val current = _state.value
            val amountPaise = AmountInput.parseToPaise(current.amountText)
            if (amountPaise == null) {
                _state.update { it.copy(amountError = true) }
                return
            }
            viewModelScope.launch {
                _state.update { it.copy(saving = true) }
                val now = clock.instant()
                val businessId = session.businessId()
                // Booking mode (ADR-106 §2.2): resolve the EVENT PARTY now, in order —
                // by link → by normalized name → create (needs manage_parties).
                val partyId =
                    if (bookingDraft != null) {
                        resolveBookingParty(bookingDraft, businessId, now) ?: run {
                            _state.update { it.copy(saving = false) }
                            return@launch
                        }
                    } else {
                        partyId
                    }
                val expense =
                    editingExpense?.copy(
                        direction = current.direction,
                        amountPaise = amountPaise,
                        expenseDate = current.date,
                        notes = current.notes.trim().ifEmpty { null },
                        updatedAt = now,
                    ) ?: Expense(
                        id = UUID.randomUUID().toString(),
                        businessId = businessId,
                        partyId = partyId,
                        direction = current.direction,
                        amountPaise = amountPaise,
                        expenseDate = current.date,
                        notes = current.notes.trim().ifEmpty { null },
                        createdBy = session.userId(),
                        createdAt = now,
                        updatedAt = now,
                        bookingId = bookingDraft?.bookingId,
                    )
                expensesRepository.saveExpense(expense)
                savedPartyId = expense.partyId
                // Deferred removals (ADR-084): the same cascade as the viewer delete
                // (ADR-053) — row tombstone + outbox DELETE, cache file, best-effort Drive
                // delete — so every device converges via the tombstone pull leg.
                removedAttachments.toList().forEach { removed -> attachmentDeleter.delete(removed) }
                removedAttachments.clear()
                current.attachments.forEach { staged ->
                    val attachment =
                        ExpenseAttachment(
                            id = UUID.randomUUID().toString(),
                            expenseId = expense.id,
                            businessId = businessId,
                            driveFileId = null, // pending until the Drive upload completes
                            mimeType = staged.mimeType,
                            fileName = staged.fileName,
                            createdAt = now,
                        )
                    ledgerRepository.saveAttachment(attachment, staged.file.absolutePath)
                    uploadQueue.enqueue(staged.file.absolutePath, expense.id)
                }
                if (current.attachments.isNotEmpty() && linkState.value is GoogleLinkState.NotLinked) {
                    _state.update { it.copy(saving = false, showGooglePrompt = true) }
                } else {
                    _events.emit(AddEntryEvent.Saved(expense.partyId))
                }
            }
        }

        /**
         * ADR-106 §2.2 event-party resolution, in this exact order:
         * 1. by LINK — a live party with `booking_id = this booking` is reused whatever its
         *    current name (the owner may have renamed it; the booking may have been edited);
         * 2. by NAME — a live party whose normalized name equals the §2.1 name (tombstones
         *    never match; earliest `created_at`); its `booking_id` is NOT back-filled;
         * 3. CREATE `{name = §2.1, phone = customer phone or null, business_related = true,
         *    booking_id}` — needs `expenses.manage_parties` (RLS `parties_insert`); without
         *    it the blocking message is emitted and NOTHING is written (null returned).
         * An existing party's phone is never back-filled.
         */
        private suspend fun resolveBookingParty(
            draft: BookingExpenseDraft,
            businessId: String,
            now: java.time.Instant,
        ): String? {
            expensesRepository.livePartyForBooking(businessId, draft.bookingId)?.let { return it.id }
            val name = draft.eventPartyName.trim()
            expensesRepository.liveParty(businessId, name)?.let { return it.id }
            if (!session.canCreateParties.first()) {
                _events.emit(AddEntryEvent.PartyCreateForbidden(name))
                return null
            }
            val party =
                Party(
                    id = UUID.randomUUID().toString(),
                    businessId = businessId,
                    name = name,
                    phone = draft.customerPhone?.trim()?.ifEmpty { null },
                    createdAt = now,
                    updatedAt = now,
                    businessRelated = true,
                    bookingId = draft.bookingId,
                )
            expensesRepository.saveParty(party)
            return party.id
        }

        /** "Later" on the Google prompt — attachments stay local-pending; finish the flow. */
        fun dismissGooglePrompt() {
            _state.update { it.copy(showGooglePrompt = false) }
            _events.tryEmit(AddEntryEvent.Saved(savedPartyId ?: partyId))
        }

        /**
         * "Connect Google" on the prompt: runs the Settings link flow in place (§4.4
         * linker). [activityContext] MUST be an Activity context — Credential Manager
         * shows UI. On success the queued attachments upload on the sync pass we nudge.
         */
        fun linkGoogle(activityContext: Context) {
            if (_state.value.linking) return
            _state.update { it.copy(linking = true) }
            viewModelScope.launch {
                googleAccountLinker
                    .link(activityContext)
                    .onSuccess { onLinked() }
                    .onFailure(::handleLinkFailure)
            }
        }

        /** Completes the link after the scope-consent sheet returned [resultIntent]. */
        fun completeGoogleConsent(resultIntent: Intent?) {
            _consentIntent.value = null
            viewModelScope.launch {
                googleAccountLinker
                    .completeLink(resultIntent)
                    .onSuccess { onLinked() }
                    .onFailure(::handleLinkFailure)
            }
        }

        private fun onLinked() {
            // The metadata rows + outbox ops were persisted before the prompt; a sync
            // nudge is all the uploads need (queue contract, ADR-018).
            syncScheduler.requestImmediateSync()
            _state.update { it.copy(showGooglePrompt = false, linking = false) }
            _events.tryEmit(AddEntryEvent.Saved(savedPartyId ?: partyId))
        }

        private fun handleLinkFailure(error: Throwable) {
            when (error) {
                is GoogleLinkException.NeedsScopeConsent -> {
                    _consentIntent.value = error.pendingIntent
                    return // keep `linking`; the consent sheet continues the flow
                }
                is GoogleLinkException.Cancelled -> Unit // keep the dialog; Later still works
                else -> _events.tryEmit(AddEntryEvent.GoogleLinkFailed)
            }
            _state.update { it.copy(linking = false) }
        }
    }
