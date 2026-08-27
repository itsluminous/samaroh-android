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
    /** Search-filtered IN-STOCK rows (quantity > 0), sorted by item name. */
    val lines: List<CurrentInventoryLine> = emptyList(),
    /** True when a non-blank search filtered out every row. */
    val noSearchResults: Boolean = false,
    /** True when master items exist but every one is at zero stock (parity: hasStock). */
    val allZero: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CurrentInventoryViewModel
    @Inject
    constructor(
        activeBusinessProvider: ActiveBusinessProvider,
        overviewRepository: InventoryOverviewRepository,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        val searchQuery: StateFlow<String> = query.asStateFlow()

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
                // Zero-quantity items stay on the Masterlist but are hidden from the
                // stock screen (parity: the stock list shows current_quantity > 0 only).
                val inStock = lines.filter { it.currentQuantity > 0 }
                val trimmed = q.trim()
                val filtered = if (trimmed.isEmpty()) inStock else inStock.filter { it.name.contains(trimmed, ignoreCase = true) }
                CurrentInventoryUiState(
                    loading = false,
                    lines = filtered,
                    noSearchResults = trimmed.isNotEmpty() && filtered.isEmpty() && inStock.isNotEmpty(),
                    allZero = lines.isNotEmpty() && inStock.isEmpty(),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CurrentInventoryUiState())

        fun onSearchQueryChange(value: String) {
            query.value = value
        }
    }
