package com.itsluminous.samaroh.feature.expenses.ledger

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.feature.expenses.ExpensesSession
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import javax.inject.Inject

/** Route argument name for the party id (shared by ledger and add-entry destinations). */
const val ARG_PARTY_ID = "partyId"

data class PartyLedgerState(
    val party: Party? = null,
    /** Newest-first entries annotated with the balance after each (§4.2). */
    val rows: List<LedgerRow> = emptyList(),
    /** expenseId → its live attachments (thumbnails + pending badges). */
    val attachmentsByExpense: Map<String, List<AttachmentWithLocalState>> = emptyMap(),
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
    /** Active business display name for the edit-party "Associated with {business}?" pill. */
    val businessName: String = "",
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
}

@HiltViewModel
class PartyLedgerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val expensesRepository: ExpensesRepository,
        private val ledgerRepository: ExpensesLedgerRepository,
        private val session: ExpensesSession,
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

        /** All permission gates as one flow (keeps the state combine at 5 sources). */
        private val gates =
            combine(
                session.canEditEntries,
                session.canCreateEntries,
                session.canDeleteEntries,
                session.canManageParties,
                session.canDeleteParties,
            ) { canEdit, canCreate, canDeleteEntry, canManage, canDeleteParty ->
                Gates(canEdit, canCreate, canDeleteEntry, canManage, canDeleteParty)
            }

        val state: StateFlow<PartyLedgerState> =
            combine(
                partyRefresh.map { ledgerRepository.party(partyId) },
                expensesRepository.entriesForParty(partyId),
                ledgerRepository.attachmentsForParty(partyId),
                gates,
                session.businessName,
            ) { party, entries, attachments, gate, businessName ->
                val rows = RunningBalanceCalculator.withRunningBalance(entries)
                PartyLedgerState(
                    party = party,
                    rows = rows,
                    attachmentsByExpense = attachments.groupBy { it.attachment.expenseId },
                    netBalancePaise = rows.firstOrNull()?.balanceAfterPaise ?: 0,
                    canEditEntries = gate.editEntries,
                    canCreateEntries = gate.createEntries,
                    canDeleteEntries = gate.deleteEntries,
                    canEditParty = gate.manageParties,
                    canDeleteParty = gate.deleteParties,
                    businessName = businessName,
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PartyLedgerState())

        /** The five §3 gates the ledger screen renders from. */
        private data class Gates(
            val editEntries: Boolean,
            val createEntries: Boolean,
            val deleteEntries: Boolean,
            val manageParties: Boolean,
            val deleteParties: Boolean,
        )

        /** Tombstone delete (§4.2); the row disappears locally and the delete syncs as a tombstone. */
        fun deleteEntry(expenseId: String) {
            viewModelScope.launch {
                expensesRepository.deleteExpense(expenseId)
            }
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
         * Delete party (ADR-028): cascade-tombstones the party, its expenses and their
         * attachments (one outbox DELETE per row), removes the local cached attachment
         * files, then emits [PartyLedgerEvent.PartyDeleted] so the UI navigates back.
         */
        fun deleteParty() {
            val party = state.value.party ?: return
            viewModelScope.launch {
                val localCachePaths = ledgerRepository.deletePartyCascade(party.id)
                localCachePaths.forEach { path -> runCatching { File(path).delete() } }
                _events.emit(PartyLedgerEvent.PartyDeleted(party.name))
            }
        }
    }
