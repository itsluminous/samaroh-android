package com.itsluminous.samaroh.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.InventoryOverviewRepository
import com.itsluminous.samaroh.core.data.repository.InventoryRepository
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.MasterItem
import com.itsluminous.samaroh.core.model.TxnType
import com.itsluminous.samaroh.feature.inventory.domain.FuzzyMatcher
import com.itsluminous.samaroh.feature.inventory.domain.parseQuantity
import com.itsluminous.samaroh.feature.inventory.domain.parseRupeesToPaise
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/** Validation errors of the record-transaction form; each maps to a catalog string. */
enum class TransactionFormError {
    ITEM_REQUIRED,
    QUANTITY_INVALID,
    PRICE_INVALID,
    INSUFFICIENT_STOCK,
}

/** A successfully recorded transaction: what the confirmation snackbar reports. */
data class SavedTransaction(
    val type: TxnType,
    /** Long paise (ADR-002): qty × unit price for adds, the FIFO cost for removes. */
    val totalValuePaise: Long,
)

/** UI state of the record-transaction dialog (§4.3). */
data class RecordTransactionUiState(
    val itemQuery: String = "",
    val suggestions: List<MasterItem> = emptyList(),
    val selectedItem: MasterItem? = null,
    val type: TxnType = TxnType.ADD,
    val quantityText: String = "",
    val unitPriceText: String = "",
    val notes: String = "",
    /** Current stock of the selected item; drives the cannot-remove-more validation. */
    val availableStock: Double? = null,
    /** Per-item stock, loaded for Remove mode: filters and annotates the picker. */
    val stockByItemId: Map<String, Double> = emptyMap(),
    val error: TransactionFormError? = null,
    val saving: Boolean = false,
    val saved: SavedTransaction? = null,
)

@HiltViewModel
class RecordTransactionViewModel
    @Inject
    constructor(
        private val businessRepository: BusinessRepository,
        private val activeBusinessProvider: ActiveBusinessProvider,
        private val inventoryRepository: InventoryRepository,
        private val overviewRepository: InventoryOverviewRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val state = MutableStateFlow(RecordTransactionUiState())
        val uiState: StateFlow<RecordTransactionUiState> = state.asStateFlow()

        /** Guards against re-preselecting (and wiping edits) on recomposition. */
        private var preselectedItemId: String? = null

        fun onItemQueryChange(value: String) {
            state.update { current ->
                val stillSelected = current.selectedItem?.takeIf { it.name == value }
                current.copy(
                    itemQuery = value,
                    selectedItem = stillSelected,
                    availableStock = if (stillSelected == null) null else current.availableStock,
                    error = null,
                )
            }
        }

        /**
         * Debounced by TypeAheadField (~300 ms). Picker fallback chain (parity with the
         * web dialog): blank query → the full item list; 1–2 characters → substring
         * filter; 3+ → fuzzy matching. In Remove mode only in-stock items are offered.
         */
        fun onItemQueryDebounced(query: String) {
            viewModelScope.launch { refreshSuggestions(query) }
        }

        /**
         * Pre-selects [itemId] (item-detail entry point) and pins the dialog to [type].
         * No-op when already pre-selected for the same item — recomposition safety.
         */
        fun preselectItem(
            itemId: String,
            type: TxnType = TxnType.ADD,
        ) {
            if (preselectedItemId == itemId && state.value.selectedItem?.id == itemId) return
            preselectedItemId = itemId
            viewModelScope.launch {
                val businessId = activeBusinessId() ?: return@launch
                val item =
                    inventoryRepository
                        .masterItems(businessId)
                        .first()
                        .firstOrNull { it.id == itemId && it.deletedAt == null } ?: return@launch
                state.value = RecordTransactionUiState(itemQuery = item.name, selectedItem = item, type = type)
                loadStockFor(item)
            }
        }

        fun onItemSelected(name: String) {
            val item = state.value.suggestions.firstOrNull { it.name == name } ?: return
            state.update {
                it.copy(itemQuery = item.name, selectedItem = item, suggestions = emptyList(), error = null)
            }
            loadStockFor(item)
        }

        fun onTypeChange(type: TxnType) {
            state.update { it.copy(type = type, error = null) }
            // Remove mode restricts the picker to in-stock items: recompute with the new mode.
            viewModelScope.launch {
                refreshSuggestions(state.value.itemQuery, onlyIfOpen = true)
                state.update { it.copy(error = liveStockError(it)) }
            }
        }

        fun onQuantityChange(value: String) {
            state.update { current ->
                val updated = current.copy(quantityText = value, error = null)
                updated.copy(error = liveStockError(updated))
            }
        }

        fun onUnitPriceChange(value: String) {
            state.update { it.copy(unitPriceText = value, error = null) }
        }

        fun onNotesChange(value: String) {
            state.update { it.copy(notes = value) }
        }

        fun save() {
            val snapshot = state.value
            if (snapshot.saving || snapshot.saved != null) return
            val item = snapshot.selectedItem
            if (item == null) {
                state.update { it.copy(error = TransactionFormError.ITEM_REQUIRED) }
                return
            }
            val quantity = parseQuantity(snapshot.quantityText)
            if (quantity == null) {
                state.update { it.copy(error = TransactionFormError.QUANTITY_INVALID) }
                return
            }
            val unitPricePaise =
                when (snapshot.type) {
                    TxnType.ADD -> {
                        val parsed = parseRupeesToPaise(snapshot.unitPriceText)
                        if (parsed == null) {
                            state.update { it.copy(error = TransactionFormError.PRICE_INVALID) }
                            return
                        }
                        parsed
                    }
                    // Removes derive their cost from the consumed FIFO lots.
                    TxnType.REMOVE -> 0L
                }
            state.update { it.copy(saving = true) }
            viewModelScope.launch {
                val stock = inventoryRepository.currentStock(item.businessId, item.id)
                if (snapshot.type == TxnType.REMOVE && quantity > stock) {
                    state.update { it.copy(saving = false, availableStock = stock, error = TransactionFormError.INSUFFICIENT_STOCK) }
                    return@launch
                }
                val business = businessRepository.business(item.businessId)
                val now = clock.instant()
                val totalValuePaise =
                    overviewRepository.recordTransactionForValue(
                        InventoryTransaction(
                            id = UUID.randomUUID().toString(),
                            businessId = item.businessId,
                            masterItemId = item.id,
                            transactionType = snapshot.type,
                            quantity = quantity,
                            unitPricePaise = unitPricePaise,
                            transactionDate = now,
                            notes = snapshot.notes.trim().ifEmpty { null },
                            // Interim author attribution until the auth wave provides a session.
                            createdBy = business?.ownerUserId.orEmpty(),
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                state.update {
                    it.copy(saving = false, saved = SavedTransaction(type = snapshot.type, totalValuePaise = totalValuePaise))
                }
            }
        }

        /**
         * Consumes a successful save (BUG-FIX, W2-B e2e): the view model is scoped to
         * the SCREEN, not the dialog, so a stale `saved` made every subsequent
         * dialog opening self-dismiss instantly — recording a second transaction was
         * impossible without leaving the tab. Resetting to a fresh state also clears
         * the previous entry's fields for the next opening.
         */
        fun consumeSaved() {
            preselectedItemId = null
            state.value = RecordTransactionUiState()
        }

        /** Live over-stock validation: flags a Remove for more than the known stock while typing. */
        private fun liveStockError(current: RecordTransactionUiState): TransactionFormError? {
            if (current.type != TxnType.REMOVE) return null
            val stock = current.availableStock ?: return null
            val quantity = parseQuantity(current.quantityText) ?: return null
            return if (quantity > stock) TransactionFormError.INSUFFICIENT_STOCK else null
        }

        private fun loadStockFor(item: MasterItem) {
            viewModelScope.launch {
                val stock = inventoryRepository.currentStock(item.businessId, item.id)
                state.update {
                    if (it.selectedItem?.id == item.id) {
                        val updated = it.copy(availableStock = stock)
                        updated.copy(error = liveStockError(updated))
                    } else {
                        it
                    }
                }
            }
        }

        private suspend fun refreshSuggestions(
            query: String,
            onlyIfOpen: Boolean = false,
        ) {
            if (onlyIfOpen && state.value.suggestions.isEmpty()) return
            val businessId = activeBusinessId() ?: return
            val items = inventoryRepository.masterItems(businessId).first().filter { it.deletedAt == null }
            val removeMode = state.value.type == TxnType.REMOVE
            val stockById =
                if (removeMode) {
                    overviewRepository
                        .currentInventory(businessId)
                        .first()
                        .associate { it.masterItemId to it.currentQuantity }
                } else {
                    emptyMap()
                }
            val candidates = if (removeMode) items.filter { (stockById[it.id] ?: 0.0) > 0.0 } else items
            val trimmed = query.trim()
            val matches =
                when {
                    trimmed.isEmpty() -> candidates.sortedBy { it.name.lowercase() }
                    trimmed.length < FuzzyMatcher.MIN_QUERY_LENGTH ->
                        candidates
                            .filter { it.name.contains(trimmed, ignoreCase = true) }
                            .sortedBy { it.name.lowercase() }
                    else ->
                        FuzzyMatcher
                            .findSimilar(
                                query = trimmed,
                                items = candidates,
                                nameOf = { it.name },
                                excludeExactMatch = false,
                            ).map { match -> match.item }
                }
            state.update { it.copy(suggestions = matches, stockByItemId = stockById) }
        }

        private suspend fun activeBusinessId(): String? = activeBusinessProvider.activeBusiness.first()?.id
    }
