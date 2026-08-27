package com.itsluminous.samaroh.feature.inventory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine
import com.itsluminous.samaroh.core.data.repository.InventoryOverviewRepository
import com.itsluminous.samaroh.core.data.repository.InventoryRepository
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.MasterItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Nav argument carrying the master-item id into the detail destination. */
const val ITEM_DETAIL_ID_ARG = "itemId"

/** Transactions revealed per "Load more" tap on the item detail screen. */
const val ITEM_DETAIL_PAGE_SIZE = 20

/** UI state of the per-item detail screen: header facts plus windowed history. */
data class ItemDetailUiState(
    val loading: Boolean = true,
    val item: MasterItem? = null,
    val currentQuantity: Double = 0.0,
    /** FIFO value of the item's open lots; Long paise (ADR-002). */
    val totalValuePaise: Long = 0L,
    /** Newest-first visible window of the item's transactions. */
    val transactions: List<InventoryTransaction> = emptyList(),
    /** Full transaction count ("Showing N of M"). */
    val totalTransactionCount: Int = 0,
    val hasMore: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItemDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        activeBusinessProvider: ActiveBusinessProvider,
        inventoryRepository: InventoryRepository,
        overviewRepository: InventoryOverviewRepository,
    ) : ViewModel() {
        val itemId: String = checkNotNull(savedStateHandle[ITEM_DETAIL_ID_ARG])

        private val visibleCount = MutableStateFlow(ITEM_DETAIL_PAGE_SIZE)

        private val activeBusinessId: Flow<String?> =
            activeBusinessProvider.activeBusiness
                .map { it?.id }
                .distinctUntilChanged()

        private val item: Flow<MasterItem?> =
            activeBusinessId.flatMapLatest { id ->
                if (id == null) {
                    flowOf(null)
                } else {
                    inventoryRepository.masterItems(id).map { items -> items.firstOrNull { it.id == itemId } }
                }
            }

        /** Stock + FIFO value come from the same aggregate the list screen uses. */
        private val line: Flow<CurrentInventoryLine?> =
            activeBusinessId.flatMapLatest { id ->
                if (id == null) {
                    flowOf(null)
                } else {
                    overviewRepository.currentInventory(id).map { lines -> lines.firstOrNull { it.masterItemId == itemId } }
                }
            }

        private val transactions: Flow<List<InventoryTransaction>> =
            activeBusinessId.flatMapLatest { id ->
                if (id == null) flowOf(emptyList()) else inventoryRepository.transactionsForItem(id, itemId)
            }

        val uiState: StateFlow<ItemDetailUiState> =
            combine(item, line, transactions, visibleCount) { item, line, txns, visible ->
                ItemDetailUiState(
                    loading = false,
                    item = item,
                    currentQuantity = line?.currentQuantity ?: 0.0,
                    totalValuePaise = line?.totalValuePaise ?: 0L,
                    transactions = txns.take(visible),
                    totalTransactionCount = txns.size,
                    hasMore = txns.size > visible,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemDetailUiState())

        /** Reveals the next page of transactions (simple windowing over the Room flow). */
        fun loadMore() {
            visibleCount.update { it + ITEM_DETAIL_PAGE_SIZE }
        }
    }
