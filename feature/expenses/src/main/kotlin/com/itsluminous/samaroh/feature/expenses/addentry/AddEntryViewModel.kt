package com.itsluminous.samaroh.feature.expenses.addentry

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.attachments.AttachmentUploadQueue
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    /** Set after save when attachments exist but Google is not linked (§4.2 prompt stub). */
    val showGooglePrompt: Boolean = false,
)

sealed interface AddEntryEvent {
    data object Saved : AddEntryEvent

    data object AttachmentLimitReached : AddEntryEvent

    data object AttachmentFailed : AddEntryEvent
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
         * Google-link state stub (§4.2): `core:google` (W1-F) is an empty shell, so link
         * status has no source yet — treat as not linked; attachments stay visibly pending
         * either way, which is the honest state.
         */
        private val googleLinked = false

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
                val prepared = compressor.prepare(uri, mimeType, displayName)
                if (prepared == null) {
                    _events.emit(AddEntryEvent.AttachmentFailed)
                } else {
                    stage(prepared)
                }
            }
        }

        /** Stages a camera capture written to [file] by the TakePicture contract. */
        fun onImageCaptured(file: File) {
            if (_state.value.attachments.size >= MAX_ATTACHMENTS) {
                _events.tryEmit(AddEntryEvent.AttachmentLimitReached)
                return
            }
            viewModelScope.launch {
                val prepared = compressor.prepareCapturedImage(file)
                file.delete() // raw capture superseded by the compressed copy
                if (prepared == null) {
                    _events.emit(AddEntryEvent.AttachmentFailed)
                } else {
                    stage(prepared)
                }
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
                if (current.attachments.isNotEmpty() && !googleLinked) {
                    _state.update { it.copy(saving = false, showGooglePrompt = true) }
                } else {
                    _events.emit(AddEntryEvent.Saved)
                }
            }
        }

        /** "Later" (or the not-yet-wired connect action) on the Google prompt — finish the flow. */
        fun dismissGooglePrompt() {
            _state.update { it.copy(showGooglePrompt = false) }
            _events.tryEmit(AddEntryEvent.Saved)
        }
    }
