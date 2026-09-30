package com.itsluminous.samaroh.feature.expenses.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.ExpenseTotals
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.settings.ListSortOrder
import com.itsluminous.samaroh.core.data.settings.ListSortPreferences
import com.itsluminous.samaroh.core.data.settings.SummaryPeriod
import com.itsluminous.samaroh.core.data.settings.SummaryPeriodPreferences
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.feature.expenses.ExpensesSession
import com.itsluminous.samaroh.feature.expenses.domain.FuzzyNameMatcher
import com.itsluminous.samaroh.feature.expenses.domain.SummaryPeriodRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
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
    /** Header totals over [summaryPeriod] (ADR-091), by `expense_date`. */
    val totals: ExpenseTotals = ExpenseTotals(gavePaise = 0, gotPaise = 0),
    /** The persisted summary window the totals card shows; defaults to this month. */
    val summaryPeriod: SummaryPeriod = SummaryPeriod.THIS_MONTH,
    val searchQuery: String = "",
    val parties: List<PartyListItem> = emptyList(),
    val hasAnyParty: Boolean = false,
    /** ADR-028 gate: `expenses.edit` OR `expenses.manage_parties`; hides the add-person FAB. */
    val canManageParties: Boolean = false,
    /** ADR-039 gate: `expenses.view_amounts`; masks totals and net balances as ₹••• when false. */
    val canViewAmounts: Boolean = true,
    /** The persisted sort order applied to [parties] (ADR-069). */
    val sortOrder: ListSortOrder = ListSortOrder.LAST_UPDATED,
)

@HiltViewModel
class ExpensesHomeViewModel
    @Inject
    constructor(
        expensesRepository: ExpensesRepository,
        private val ledgerRepository: ExpensesLedgerRepository,
        session: ExpensesSession,
        private val sortPreferences: ListSortPreferences,
        private val summaryPeriodPreferences: SummaryPeriodPreferences,
        private val clock: Clock,
    ) : ViewModel() {
        private val searchQuery = MutableStateFlow("")

        /**
         * Totals over the persisted summary period (ADR-091): the window is resolved on the
         * device-local date each time the period changes; All time keeps the unbounded query.
         */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private fun periodTotals(businessId: String): Flow<Pair<SummaryPeriod, ExpenseTotals>> =
            summaryPeriodPreferences.expensesSummaryPeriod.flatMapLatest { period ->
                val bounds = SummaryPeriodRange.bounds(period, SummaryPeriodRange.today(clock))
                val totals =
                    if (bounds == null) {
                        ledgerRepository.totals(businessId)
                    } else {
                        ledgerRepository.totalsBetween(businessId, bounds.start, bounds.endInclusive)
                    }
                totals.map { period to it }
            }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val state: StateFlow<ExpensesHomeState> =
            session.businessIdFlow
                .flatMapLatest { businessId ->
                    combine(
                        expensesRepository.partiesWithBalance(businessId),
                        periodTotals(businessId),
                        ledgerRepository.lastEntryPerParty(businessId),
                        searchQuery,
                        // Both session gates + the sort pref as one source (keeps the combine at 5 flows).
                        combine(
                            session.canManageParties,
                            session.canViewAmounts,
                            sortPreferences.expensesPartiesSort,
                        ) { manage, amounts, sort -> Triple(manage, amounts, sort) },
                    ) { parties, (summaryPeriod, totals), lastEntries, query, gates ->
                        val (canManageParties, canViewAmounts, sortOrder) = gates
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
                            summaryPeriod = summaryPeriod,
                            searchQuery = query,
                            parties = items.filterBy(query).sortedWith(sortOrder.partyComparator()),
                            hasAnyParty = items.isNotEmpty(),
                            canManageParties = canManageParties,
                            canViewAmounts = canViewAmounts,
                            sortOrder = sortOrder,
                        )
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpensesHomeState())

        fun onSearchQueryChange(query: String) {
            searchQuery.value = query
        }

        /** Persists the chosen summary window (per device — ADR-091); totals re-query through [state]. */
        fun onSummaryPeriodChange(period: SummaryPeriod) {
            viewModelScope.launch { summaryPeriodPreferences.setExpensesSummaryPeriod(period) }
        }

        /** Persists the chosen order (per device, party-list key — ADR-069). */
        fun onSortOrderChange(order: ListSortOrder) {
            viewModelScope.launch { sortPreferences.setExpensesPartiesSort(order) }
        }

        private fun ListSortOrder.partyComparator(): Comparator<PartyListItem> =
            when (this) {
                // Parties with no entries yet (null lastEntryAt) sink to the end;
                // equal times fall back to name for a stable order.
                ListSortOrder.LAST_UPDATED ->
                    compareByDescending<PartyListItem> { it.lastEntryAt ?: Instant.MIN }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.party.name }
                ListSortOrder.NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.party.name }
                ListSortOrder.NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.party.name }
            }

        private fun List<PartyListItem>.filterBy(query: String): List<PartyListItem> {
            val normalized = FuzzyNameMatcher.normalize(query)
            if (normalized.isEmpty()) return this
            return filter { FuzzyNameMatcher.normalize(it.party.name).contains(normalized) }
        }
    }
