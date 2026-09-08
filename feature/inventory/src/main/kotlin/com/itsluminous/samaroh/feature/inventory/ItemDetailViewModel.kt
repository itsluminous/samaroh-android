package com.itsluminous.samaroh.feature.inventory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine
import com.itsluminous.samaroh.core.data.repository.InventoryOverviewRepository
import com.itsluminous.samaroh.core.data.repository.InventoryRepository
import com.itsluminous.samaroh.core.data.repository.TransactionMutationResult
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.MasterItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/** One-shot outcomes of a transaction edit/delete (ADR-070) — snackbar inputs. */
enum class TransactionMutationEvent {
    UPDATED,
    DELETED,

    /** The mutation would make cumulative stock negative somewhere in the history. */
    REJECTED_NEGATIVE_STOCK,
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItemDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        activeBusinessProvider: ActiveBusinessProvider,
        inventoryRepository: InventoryRepository,
        private val overviewRepository: InventoryOverviewRepository,
        session: InventorySession,
    ) : ViewModel() {
        val itemId: String = checkNotNull(savedStateHandle[ITEM_DETAIL_ID_ARG])

        /** `inventory.create` gate (§4.3): hides the header's Add/Remove buttons. */
        val canRecordTransactions: StateFlow<Boolean> =
            session.canRecordTransactions
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /** `inventory.edit` gate (ADR-070): shows Edit in a transaction row's menu. */
        val canEditTransactions: StateFlow<Boolean> =
            session.canEditTransactions
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /** `inventory.delete` gate (ADR-070): shows Delete in a transaction row's menu. */
        val canDeleteTransactions: StateFlow<Boolean> =
            session.canDeleteTransactions
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /** `inventory.view_amounts` gate (ADR-039): masks total value and unit prices as ₹•••. */
        val canViewAmounts: StateFlow<Boolean> =
            session.canViewAmounts
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

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

        private val mutationEvents = Channel<TransactionMutationEvent>(Channel.BUFFERED)

        /** One-shot edit/delete outcomes (ADR-070): updated/deleted confirmations + rejections. */
        val events: Flow<TransactionMutationEvent> = mutationEvents.receiveAsFlow()

        /**
         * Saves an edited transaction (quantity/price/date/notes) with a full FIFO
         * replay of the item's history (ADR-070); rejected edits leave the data
         * untouched and surface the localized negative-stock error.
         */
        fun updateTransaction(edited: InventoryTransaction) {
            viewModelScope.launch {
                val result = overviewRepository.updateTransaction(edited)
                mutationEvents.send(
                    when (result) {
                        TransactionMutationResult.SAVED -> TransactionMutationEvent.UPDATED
                        TransactionMutationResult.REJECTED_NEGATIVE_STOCK -> TransactionMutationEvent.REJECTED_NEGATIVE_STOCK
                    },
                )
            }
        }

        /** Tombstone-deletes a transaction after the same replay validation (ADR-070). */
        fun deleteTransaction(id: String) {
            viewModelScope.launch {
                val result = overviewRepository.deleteTransaction(id)
                mutationEvents.send(
                    when (result) {
                        TransactionMutationResult.SAVED -> TransactionMutationEvent.DELETED
                        TransactionMutationResult.REJECTED_NEGATIVE_STOCK -> TransactionMutationEvent.REJECTED_NEGATIVE_STOCK
                    },
                )
            }
        }
    }
