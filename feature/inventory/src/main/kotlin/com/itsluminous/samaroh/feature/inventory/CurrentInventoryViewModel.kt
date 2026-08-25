package com.itsluminous.samaroh.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine
import com.itsluminous.samaroh.core.data.repository.InventoryOverviewRepository
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
    /** Search-filtered rows, sorted by item name. */
    val lines: List<CurrentInventoryLine> = emptyList(),
    /** True when a non-blank search filtered out every row. */
    val noSearchResults: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CurrentInventoryViewModel
    @Inject
    constructor(
        businessRepository: BusinessRepository,
        overviewRepository: InventoryOverviewRepository,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        val searchQuery: StateFlow<String> = query.asStateFlow()

        /**
         * Interim active-business resolution: the first live business. The dedicated
         * business switcher/session lands with the onboarding wave.
         */
        private val activeBusinessId: Flow<String?> =
            businessRepository
                .businesses()
                .map { list -> list.firstOrNull { it.deletedAt == null }?.id }
                .distinctUntilChanged()

        val uiState: StateFlow<CurrentInventoryUiState> =
            combine(
                activeBusinessId.flatMapLatest { id ->
                    if (id == null) flowOf(emptyList()) else overviewRepository.currentInventory(id)
                },
                query,
            ) { lines, q ->
                val trimmed = q.trim()
                val filtered = if (trimmed.isEmpty()) lines else lines.filter { it.name.contains(trimmed, ignoreCase = true) }
                CurrentInventoryUiState(
                    loading = false,
                    lines = filtered,
                    noSearchResults = trimmed.isNotEmpty() && filtered.isEmpty() && lines.isNotEmpty(),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CurrentInventoryUiState())

        fun onSearchQueryChange(value: String) {
            query.value = value
        }
    }
