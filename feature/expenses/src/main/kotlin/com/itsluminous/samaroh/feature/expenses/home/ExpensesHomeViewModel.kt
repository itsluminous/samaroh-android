package com.itsluminous.samaroh.feature.expenses.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.ExpenseTotals
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.feature.expenses.ExpensesSession
import com.itsluminous.samaroh.feature.expenses.domain.FuzzyNameMatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import javax.inject.Inject

/** One row of the Expenses home party list (§4.2). */
data class PartyListItem(
    val party: Party,
    /** Σ(paid) − Σ(received) in paise; positive rendered red, negative green. */
    val netBalancePaise: Long,
    /** Most recent entry time, or null when the party has no entries yet. */
    val lastEntryAt: Instant?,
) {
    /** Avatar initials: first letters of up to the first two words. */
    val initials: String =
        party.name
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
}

data class ExpensesHomeState(
    val totals: ExpenseTotals = ExpenseTotals(gavePaise = 0, gotPaise = 0),
    val searchQuery: String = "",
    val parties: List<PartyListItem> = emptyList(),
    val hasAnyParty: Boolean = false,
    /** ADR-028 gate: `expenses.edit` OR `expenses.manage_parties`; hides the add-person FAB. */
    val canManageParties: Boolean = false,
    /** ADR-039 gate: `expenses.view_amounts`; masks totals and net balances as ₹••• when false. */
    val canViewAmounts: Boolean = true,
)

@HiltViewModel
class ExpensesHomeViewModel
    @Inject
    constructor(
        expensesRepository: ExpensesRepository,
        ledgerRepository: ExpensesLedgerRepository,
        session: ExpensesSession,
    ) : ViewModel() {
        private val searchQuery = MutableStateFlow("")

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val state: StateFlow<ExpensesHomeState> =
            session.businessIdFlow
                .flatMapLatest { businessId ->
                    combine(
                        expensesRepository.partiesWithBalance(businessId),
                        ledgerRepository.totals(businessId),
                        ledgerRepository.lastEntryPerParty(businessId),
                        searchQuery,
                        // Both session gates as one source (keeps the combine at 5 flows).
                        combine(session.canManageParties, session.canViewAmounts) { manage, amounts -> manage to amounts },
                    ) { parties, totals, lastEntries, query, gates ->
                        val (canManageParties, canViewAmounts) = gates
                        val items =
                            parties.map {
                                PartyListItem(
                                    party = it.party,
                                    netBalancePaise = it.netBalancePaise,
                                    lastEntryAt = lastEntries[it.party.id],
                                )
                            }
                        ExpensesHomeState(
                            totals = totals,
                            searchQuery = query,
                            parties = items.filterBy(query),
                            hasAnyParty = items.isNotEmpty(),
                            canManageParties = canManageParties,
                            canViewAmounts = canViewAmounts,
                        )
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpensesHomeState())

        fun onSearchQueryChange(query: String) {
            searchQuery.value = query
        }

        private fun List<PartyListItem>.filterBy(query: String): List<PartyListItem> {
            val normalized = FuzzyNameMatcher.normalize(query)
            if (normalized.isEmpty()) return this
            return filter { FuzzyNameMatcher.normalize(it.party.name).contains(normalized) }
        }
    }
