package com.itsluminous.samaroh.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine
import com.itsluminous.samaroh.core.data.repository.InventoryOverviewRepository
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** UI state of the Current Inventory screen (§4.3). */
data class CurrentInventoryUiState(
    val loading: Boolean = true,
    /**
     * Search-filtered rows: in-stock items (quantity > 0) first, then zero-stock items,
     * each group sorted by name (ADR-057 — zero-stock masterlist items no longer vanish
     * from the stock screen; they render dimmed with 0 quantity and ₹0 value).
     */
    val lines: List<CurrentInventoryLine> = emptyList(),
    /** True when a non-blank search filtered out every row. */
    val noSearchResults: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CurrentInventoryViewModel
    @Inject
    constructor(
        activeBusinessProvider: ActiveBusinessProvider,
        overviewRepository: InventoryOverviewRepository,
        session: InventorySession,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        val searchQuery: StateFlow<String> = query.asStateFlow()

        /** `inventory.create` gate (§4.3): hides the record-transaction FAB entirely. */
        val canRecordTransactions: StateFlow<Boolean> =
            session.canRecordTransactions
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /** `inventory.view_amounts` gate (ADR-039): masks stock values as ₹••• (quantities stay). */
        val canViewAmounts: StateFlow<Boolean> =
            session.canViewAmounts
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

        /** App-wide active-business session seam (docs/decisions.md ADR-017). */
        private val activeBusinessId: Flow<String?> =
            activeBusinessProvider.activeBusiness
                .map { it?.id }
                .distinctUntilChanged()

        val uiState: StateFlow<CurrentInventoryUiState> =
            combine(
                activeBusinessId.flatMapLatest { id ->
                    if (id == null) flowOf(emptyList()) else overviewRepository.currentInventory(id)
                },
                query,
            ) { lines, q ->
                // ADR-057: zero-quantity items are SHOWN, after the in-stock rows —
                // hiding them made users think their masterlist items vanished. The DAO
                // orders by name, and partition is stable, so each group stays
                // alphabetical. Search matches zero-stock items too.
                val trimmed = q.trim()
                val matches = if (trimmed.isEmpty()) lines else lines.filter { it.name.contains(trimmed, ignoreCase = true) }
                val (inStock, zeroStock) = matches.partition { it.currentQuantity > 0 }
                CurrentInventoryUiState(
                    loading = false,
                    lines = inStock + zeroStock,
                    noSearchResults = trimmed.isNotEmpty() && matches.isEmpty() && lines.isNotEmpty(),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CurrentInventoryUiState())

        fun onSearchQueryChange(value: String) {
            query.value = value
        }
    }
