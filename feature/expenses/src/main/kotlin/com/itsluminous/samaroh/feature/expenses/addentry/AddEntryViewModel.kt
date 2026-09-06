package com.itsluminous.samaroh.feature.expenses.addentry

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.attachments.AttachmentUploadQueue
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkException
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.ExpenseAttachment
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.feature.expenses.ExpensesSession
import com.itsluminous.samaroh.feature.expenses.attachments.AttachmentCompressor
import com.itsluminous.samaroh.feature.expenses.domain.AmountInput
import com.itsluminous.samaroh.feature.expenses.ledger.ARG_PARTY_ID
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
    val attachments: List<StagedAttachment> = emptyList(),
    val saving: Boolean = false,
    /** Set after save when attachments exist but no Google account is linked (§4.2 prompt). */
    val showGooglePrompt: Boolean = false,
    /** The prompt's Link button is running the account-picker/consent flow. */
    val linking: Boolean = false,
)

sealed interface AddEntryEvent {
    data object Saved : AddEntryEvent

    data object AttachmentLimitReached : AddEntryEvent

    data object AttachmentFailed : AddEntryEvent

    /** The picked document exceeds the attachment size cap (distinct, actionable message). */
    data object AttachmentTooLarge : AddEntryEvent

    /** The link-Google flow launched from the prompt failed (not a cancel). */
    data object GoogleLinkFailed : AddEntryEvent
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
        private val session: ExpensesSession,
        private val googleAccountLinker: GoogleAccountLinker,
        private val syncScheduler: SyncScheduler,
        private val clock: Clock,
    ) : ViewModel() {
        val partyId: String = checkNotNull(savedStateHandle[ARG_PARTY_ID])
        private val direction = ExpenseDirection.fromWire(checkNotNull(savedStateHandle[ARG_DIRECTION]))

        /** Non-null when editing an existing entry (gated by `expenses.edit`, §4.2). */
        private val editingExpenseId: String? = savedStateHandle.get<String>(ARG_EXPENSE_ID)?.ifEmpty { null }
        private var editingExpense: Expense? = null

        private val _state =
            MutableStateFlow(
                AddEntryState(direction = direction, date = LocalDate.now(clock.withZone(ZoneId.systemDefault()))),
            )
        val state: StateFlow<AddEntryState> = _state.asStateFlow()

        init {
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
                        )
                    }
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
            if (_state.value.attachments.size >= MAX_ATTACHMENTS) {
                _events.tryEmit(AddEntryEvent.AttachmentLimitReached)
                return
            }
            viewModelScope.launch {
                acceptPrepared(compressor.prepare(uri, mimeType, displayName))
            }
        }

        /** Stages a camera capture written to [file] by the TakePicture contract. */
        fun onImageCaptured(file: File) {
            if (_state.value.attachments.size >= MAX_ATTACHMENTS) {
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
                    )
                expensesRepository.saveExpense(expense)
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
                    _events.emit(AddEntryEvent.Saved)
                }
            }
        }

        /** "Later" on the Google prompt — attachments stay local-pending; finish the flow. */
        fun dismissGooglePrompt() {
            _state.update { it.copy(showGooglePrompt = false) }
            _events.tryEmit(AddEntryEvent.Saved)
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
            _events.tryEmit(AddEntryEvent.Saved)
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
