package com.itsluminous.samaroh.feature.expenses.ledger

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.feature.expenses.ExpensesSession
import com.itsluminous.samaroh.feature.expenses.domain.LedgerRow
import com.itsluminous.samaroh.feature.expenses.domain.RunningBalanceCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    /** Active business display name for the edit-party "Associated with {business}?" pill. */
    val businessName: String = "",
    val loaded: Boolean = false,
)

@HiltViewModel
class PartyLedgerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val expensesRepository: ExpensesRepository,
        private val ledgerRepository: ExpensesLedgerRepository,
        session: ExpensesSession,
        private val clock: Clock,
    ) : ViewModel() {
        val partyId: String = checkNotNull(savedStateHandle[ARG_PARTY_ID])

        /** Bumped after a party edit so the one-shot party lookup re-emits the fresh row. */
        private val partyRefresh = MutableStateFlow(0)

        val state: StateFlow<PartyLedgerState> =
            combine(
                partyRefresh.map { ledgerRepository.party(partyId) },
                expensesRepository.entriesForParty(partyId),
                ledgerRepository.attachmentsForParty(partyId),
                session.canEditEntries,
                session.businessName,
            ) { party, entries, attachments, canEdit, businessName ->
                val rows = RunningBalanceCalculator.withRunningBalance(entries)
                PartyLedgerState(
                    party = party,
                    rows = rows,
                    attachmentsByExpense = attachments.groupBy { it.attachment.expenseId },
                    netBalancePaise = rows.firstOrNull()?.balanceAfterPaise ?: 0,
                    canEditEntries = canEdit,
                    businessName = businessName,
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PartyLedgerState())

        /** Tombstone delete (§4.2); the row disappears locally and the delete syncs as a tombstone. */
        fun deleteEntry(expenseId: String) {
            viewModelScope.launch {
                expensesRepository.deleteExpense(expenseId)
            }
        }

        /** Edit-party toggle (ADR-027): flips the business/personal flag via Room + outbox. */
        fun setBusinessRelated(businessRelated: Boolean) {
            val party = state.value.party ?: return
            if (party.businessRelated == businessRelated) return
            viewModelScope.launch {
                expensesRepository.saveParty(
                    party.copy(businessRelated = businessRelated, updatedAt = clock.instant()),
                )
                partyRefresh.update { it + 1 }
            }
        }
    }
