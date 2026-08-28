package com.itsluminous.samaroh.feature.expenses

import androidx.lifecycle.ViewModel
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Gate for App-Link ledger targets (ADR-033): the tab host checks the party exists
 * locally before navigating to its ledger, so a stale/foreign id from a shared web URL
 * gracefully lands on the party list instead of an empty ledger screen.
 */
@HiltViewModel
class ExpensesDeepLinkViewModel
    @Inject
    constructor(
        private val ledgerRepository: ExpensesLedgerRepository,
    ) : ViewModel() {
        /** True when [partyId] resolves to a live local party row. */
        suspend fun partyExists(partyId: String): Boolean = ledgerRepository.party(partyId) != null
    }
