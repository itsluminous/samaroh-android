package com.itsluminous.samaroh.feature.inventory

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.InventoryOverviewRepository
import com.itsluminous.samaroh.core.data.repository.InventoryRepository
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.model.MasterItem
import com.itsluminous.samaroh.feature.inventory.domain.FuzzyMatcher
import com.itsluminous.samaroh.feature.inventory.image.ItemImageStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/** Unit dropdown options (§4.3). [wire] is the stored value; CUSTOM stores free text. */
enum class UnitOption(
    val wire: String?,
) {
    PIECES("pcs"),
    QUANTITY("qty"),
    KG("kg"),
    LITRE("litre"),
    CUSTOM(null),
    ;

    companion object {
        fun fromWire(unit: String): UnitOption = entries.firstOrNull { it.wire == unit } ?: CUSTOM
    }
}

/** Validation errors of the item editor; each maps to a catalog string. */
enum class MasterItemFormError {
    NAME_REQUIRED,
    UNIT_REQUIRED,
    DUPLICATE_NAME,
}

/** A fuzzy duplicate suggestion: the existing item plus its similarity percentage. */
data class DuplicateSuggestion(
    val item: MasterItem,
    /** Rounded similarity in 0..100, shown on the chip ("name (NN%)"). */
    val percent: Int,
)

/** State of the add/edit item dialog. */
data class MasterItemEditorState(
    /** Null id = creating a new item. */
    val editingItem: MasterItem? = null,
    val name: String = "",
    val unitOption: UnitOption = UnitOption.PIECES,
    val customUnit: String = "",
    val imagePath: String? = null,
    /** Fuzzy duplicate suggestions (3+ chars, 40% similarity), best match first. */
    val duplicates: List<DuplicateSuggestion> = emptyList(),
    val error: MasterItemFormError? = null,
    val saving: Boolean = false,
)

/** State of the delete flow: confirmation for deletable items, a blocked notice otherwise. */
data class DeleteRequestState(
    val item: MasterItem,
    /** False when transactions exist for the item — the can-delete rule refuses deletion. */
    val deletable: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MasterlistViewModel
    @Inject
    constructor(
        activeBusinessProvider: ActiveBusinessProvider,
        private val inventoryRepository: InventoryRepository,
        private val overviewRepository: InventoryOverviewRepository,
        private val imageStore: ItemImageStore,
        private val clock: Clock,
    ) : ViewModel() {
        private val activeBusinessId: Flow<String?> =
            activeBusinessProvider.activeBusiness
                .map { it?.id }
                .distinctUntilChanged()

        val items: StateFlow<List<MasterItem>> =
            activeBusinessId
                .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else inventoryRepository.masterItems(id) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        private val editorState = MutableStateFlow<MasterItemEditorState?>(null)
        val editor: StateFlow<MasterItemEditorState?> = editorState.asStateFlow()

        private val deleteRequestState = MutableStateFlow<DeleteRequestState?>(null)
        val deleteRequest: StateFlow<DeleteRequestState?> = deleteRequestState.asStateFlow()

        fun openEditor(item: MasterItem? = null) {
            editorState.value =
                if (item == null) {
                    MasterItemEditorState()
                } else {
                    MasterItemEditorState(
                        editingItem = item,
                        name = item.name,
                        unitOption = UnitOption.fromWire(item.unit),
                        customUnit = if (UnitOption.fromWire(item.unit) == UnitOption.CUSTOM) item.unit else "",
                        imagePath = item.imagePath,
                    )
                }
        }

        fun dismissEditor() {
            editorState.value = null
        }

        fun onNameChange(value: String) {
            val editingId = editorState.value?.editingItem?.id
            val duplicates =
                FuzzyMatcher
                    .findSimilar(
                        query = value,
                        items = items.value.filter { it.id != editingId },
                        nameOf = { it.name },
                        threshold = FuzzyMatcher.DUPLICATE_THRESHOLD,
                    ).map { DuplicateSuggestion(item = it.item, percent = (it.score * 100).roundToInt()) }
            editorState.update { it?.copy(name = value, duplicates = duplicates, error = null) }
        }

        /** Tapping a duplicate chip switches the editor to that existing item instead. */
        fun onDuplicateSelected(item: MasterItem) {
            openEditor(item)
        }

        fun onUnitOptionChange(option: UnitOption) {
            editorState.update { it?.copy(unitOption = option, error = null) }
        }

        fun onCustomUnitChange(value: String) {
            editorState.update { it?.copy(customUnit = value, error = null) }
        }

        fun onImagePicked(uri: Uri) {
            val itemId = editorState.value?.editingItem?.id ?: UUID.randomUUID().toString()
            viewModelScope.launch {
                val path = imageStore.compressItemImage(uri, itemId)
                if (path != null) editorState.update { it?.copy(imagePath = path) }
            }
        }

        fun onImageRemoved() {
            editorState.update { it?.copy(imagePath = null) }
        }

        fun saveItem(businessIdOverride: String? = null) {
            val snapshot = editorState.value ?: return
            if (snapshot.saving) return
            val name = snapshot.name.trim()
            if (name.isEmpty()) {
                editorState.update { it?.copy(error = MasterItemFormError.NAME_REQUIRED) }
                return
            }
            val unit = snapshot.unitOption.wire ?: snapshot.customUnit.trim()
            if (unit.isEmpty()) {
                editorState.update { it?.copy(error = MasterItemFormError.UNIT_REQUIRED) }
                return
            }
            val clash =
                items.value.any {
                    it.id != snapshot.editingItem?.id && it.deletedAt == null && it.name.equals(name, ignoreCase = true)
                }
            if (clash) {
                editorState.update { it?.copy(error = MasterItemFormError.DUPLICATE_NAME) }
                return
            }
            editorState.update { it?.copy(saving = true) }
            viewModelScope.launch {
                val businessId = businessIdOverride ?: snapshot.editingItem?.businessId ?: activeBusinessId.first()
                if (businessId == null) {
                    editorState.update { it?.copy(saving = false) }
                    return@launch
                }
                val now = clock.instant()
                val existing = snapshot.editingItem
                inventoryRepository.saveMasterItem(
                    existing?.copy(name = name, unit = unit, imagePath = snapshot.imagePath, updatedAt = now)
                        ?: MasterItem(
                            id = UUID.randomUUID().toString(),
                            businessId = businessId,
                            name = name,
                            unit = unit,
                            imagePath = snapshot.imagePath,
                            createdAt = now,
                            updatedAt = now,
                        ),
                )
                // An explicitly removed photo must not leak on disk (deleted only after a
                // committed save — cancelling the dialog keeps the file).
                if (existing?.imagePath != null && snapshot.imagePath == null) {
                    imageStore.deleteItemImage(existing.id)
                }
                editorState.value = null
            }
        }

        /** Applies the can-delete rule before showing either the confirm or blocked dialog. */
        fun requestDelete(item: MasterItem) {
            viewModelScope.launch {
                val deletable = overviewRepository.canDeleteMasterItem(item.id)
                deleteRequestState.value = DeleteRequestState(item = item, deletable = deletable)
            }
        }

        fun confirmDelete() {
            val request = deleteRequestState.value ?: return
            if (!request.deletable) return
            viewModelScope.launch {
                inventoryRepository.deleteMasterItem(request.item.id)
                // The deleted item's photo must not leak on disk.
                if (request.item.imagePath != null) imageStore.deleteItemImage(request.item.id)
                deleteRequestState.value = null
            }
        }

        fun dismissDelete() {
            deleteRequestState.value = null
        }
    }
