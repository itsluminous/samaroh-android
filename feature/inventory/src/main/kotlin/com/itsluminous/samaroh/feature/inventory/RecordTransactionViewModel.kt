package com.itsluminous.samaroh.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
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
    val error: TransactionFormError? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class RecordTransactionViewModel
    @Inject
    constructor(
        private val businessRepository: BusinessRepository,
        private val activeBusinessProvider: ActiveBusinessProvider,
        private val inventoryRepository: InventoryRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val state = MutableStateFlow(RecordTransactionUiState())
        val uiState: StateFlow<RecordTransactionUiState> = state.asStateFlow()

        fun onItemQueryChange(value: String) {
            state.update { current ->
                val stillSelected = current.selectedItem?.takeIf { it.name == value }
                current.copy(
                    itemQuery = value,
                    selectedItem = stillSelected,
                    availableStock = if (stillSelected == null) null else current.availableStock,
                    suggestions = if (value.isBlank()) emptyList() else current.suggestions,
                    error = null,
                )
            }
        }

        /** Debounced by TypeAheadField (~300 ms): fuzzy-filters master items for the picker. */
        fun onItemQueryDebounced(query: String) {
            viewModelScope.launch {
                val businessId = activeBusinessId() ?: return@launch
                val items = inventoryRepository.masterItems(businessId).first().filter { it.deletedAt == null }
                val matches =
                    FuzzyMatcher.findSimilar(
                        query = query,
                        items = items,
                        nameOf = { it.name },
                        excludeExactMatch = false,
                    )
                state.update { it.copy(suggestions = matches.map { match -> match.item }) }
            }
        }

        fun onItemSelected(name: String) {
            val item = state.value.suggestions.firstOrNull { it.name == name } ?: return
            state.update {
                it.copy(itemQuery = item.name, selectedItem = item, suggestions = emptyList(), error = null)
            }
            viewModelScope.launch {
                val stock = inventoryRepository.currentStock(item.businessId, item.id)
                state.update { if (it.selectedItem?.id == item.id) it.copy(availableStock = stock) else it }
            }
        }

        fun onTypeChange(type: TxnType) {
            state.update { it.copy(type = type, error = null) }
        }

        fun onQuantityChange(value: String) {
            state.update { it.copy(quantityText = value, error = null) }
        }

        fun onUnitPriceChange(value: String) {
            state.update { it.copy(unitPriceText = value, error = null) }
        }

        fun onNotesChange(value: String) {
            state.update { it.copy(notes = value) }
        }

        fun save() {
            val snapshot = state.value
            if (snapshot.saving || snapshot.saved) return
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
                inventoryRepository.recordTransaction(
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
                state.update { it.copy(saving = false, saved = true) }
            }
        }

        /**
         * Consumes a successful save (BUG-FIX, W2-B e2e): the view model is scoped to
         * the SCREEN, not the dialog, so a stale `saved = true` made every subsequent
         * dialog opening self-dismiss instantly — recording a second transaction was
         * impossible without leaving the tab. Resetting to a fresh state also clears
         * the previous entry's fields for the next opening.
         */
        fun consumeSaved() {
            state.value = RecordTransactionUiState()
        }

        private suspend fun activeBusinessId(): String? = activeBusinessProvider.activeBusiness.first()?.id
    }
