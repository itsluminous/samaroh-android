package com.itsluminous.samaroh.feature.menu.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.data.repository.EventTypeRepository
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.core.model.EventTypeKind
import com.itsluminous.samaroh.feature.menu.data.CurrentBusinessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/** The add/edit dialog's working copy; [id] null = adding a new preset. */
data class EventTypeDraft(
    val id: String? = null,
    val label: String = "",
    val icon: String = "✨",
    val colorKey: String? = null,
    /** booking | marker (ADR-041) — the "Used for" pill row's selection. */
    val kind: EventTypeKind = EventTypeKind.BOOKING,
    /** True when the entered label collides with another live preset (ADR-032). */
    val duplicateLabel: Boolean = false,
)

/** Manage-screen state (Menu → Settings → Event types, ADR-032). */
data class EventTypesUiState(
    val loading: Boolean = true,
    val businessId: String? = null,
    /** Owner or `settings.manage_business` — the screen and its row are gated on this. */
    val canManage: Boolean = false,
    /** Live presets in sort order. */
    val presets: List<EventType> = emptyList(),
)

@HiltViewModel
class EventTypesViewModel
    @Inject
    constructor(
        currentBusinessProvider: CurrentBusinessProvider,
        private val eventTypeRepository: EventTypeRepository,
        permissionGuard: PermissionGuard,
        /** Palette for the colour picker + row dots (moved to core:data in ADR-032). */
        val bookingColorsProvider: BookingColorCatalog,
        private val syncScheduler: SyncScheduler,
        private val clock: Clock,
    ) : ViewModel() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<EventTypesUiState> =
            currentBusinessProvider.currentBusiness
                .flatMapLatest { business ->
                    if (business == null) {
                        flowOf(EventTypesUiState(loading = false))
                    } else {
                        combine(
                            eventTypeRepository.presets(business.id),
                            permissionGuard.permissions(business.id),
                            permissionGuard.isOwner(business.id),
                        ) { presets, permissions, isOwner ->
                            EventTypesUiState(
                                loading = false,
                                businessId = business.id,
                                canManage = isOwner || permissions.settings.manageBusiness,
                                presets = presets,
                            )
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventTypesUiState())

        /** The add/edit dialog's draft; null = dialog closed. */
        private val _draft = MutableStateFlow<EventTypeDraft?>(null)
        val draft: StateFlow<EventTypeDraft?> = _draft.asStateFlow()

        /** The preset pending delete confirmation; null = dialog closed. */
        private val _pendingDelete = MutableStateFlow<EventType?>(null)
        val pendingDelete: StateFlow<EventType?> = _pendingDelete.asStateFlow()

        // ---- add / edit dialog ----

        fun startAdd() {
            _draft.value = EventTypeDraft()
        }

        fun startEdit(preset: EventType) {
            _draft.value =
                EventTypeDraft(id = preset.id, label = preset.label, icon = preset.icon, colorKey = preset.color, kind = preset.kind)
        }

        fun dismissDraft() {
            _draft.value = null
        }

        fun setDraftLabel(value: String) = _draft.update { it?.copy(label = value, duplicateLabel = false) }

        fun setDraftIcon(value: String) = _draft.update { it?.copy(icon = value) }

        fun setDraftColor(colorKey: String?) = _draft.update { it?.copy(colorKey = colorKey) }

        fun setDraftKind(kind: EventTypeKind) = _draft.update { it?.copy(kind = kind) }

        /** Validates (non-blank, no live duplicate) and persists the draft. */
        fun saveDraft() {
            val businessId = uiState.value.businessId ?: return
            if (!uiState.value.canManage) return
            val current = _draft.value ?: return
            val label = current.label.trim()
            if (label.isEmpty()) return
            viewModelScope.launch {
                if (eventTypeRepository.labelInUse(businessId, label, excludingId = current.id)) {
                    _draft.update { it?.copy(duplicateLabel = true) }
                    return@launch
                }
                val now = clock.instant()
                val existing = current.id?.let { eventTypeRepository.preset(it) }
                val preset =
                    existing?.copy(
                        label = label,
                        icon = current.icon.ifBlank { "✨" },
                        color = current.colorKey,
                        kind = current.kind,
                        updatedAt = now,
                    ) ?: EventType(
                        id = UUID.randomUUID().toString(),
                        businessId = businessId,
                        label = label,
                        icon = current.icon.ifBlank { "✨" },
                        color = current.colorKey,
                        sortOrder = (uiState.value.presets.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                        kind = current.kind,
                        createdAt = now,
                        updatedAt = now,
                    )
                eventTypeRepository.savePreset(preset)
                _draft.value = null
                syncScheduler.requestImmediateSync()
            }
        }

        // ---- delete (soft — old bookings keep their recorded type) ----

        fun requestDelete(preset: EventType) {
            _pendingDelete.value = preset
        }

        fun dismissDelete() {
            _pendingDelete.value = null
        }

        fun confirmDelete() {
            val preset = _pendingDelete.value ?: return
            if (!uiState.value.canManage) return
            viewModelScope.launch {
                eventTypeRepository.deletePreset(preset.id)
                _pendingDelete.value = null
                syncScheduler.requestImmediateSync()
            }
        }

        // ---- reorder (up/down arrows swap adjacent sort_order values) ----

        fun move(
            preset: EventType,
            up: Boolean,
        ) {
            if (!uiState.value.canManage) return
            val presets = uiState.value.presets
            val index = presets.indexOfFirst { it.id == preset.id }
            if (index < 0) return
            val otherIndex = if (up) index - 1 else index + 1
            if (otherIndex !in presets.indices) return
            val other = presets[otherIndex]
            val now = clock.instant()
            viewModelScope.launch {
                // Swap the two rows' effective positions. Equal sort_order values (the
                // seed uses distinct ones, but be safe) fall back to swapping indices.
                val a = presets[index]
                val aOrder = if (a.sortOrder != other.sortOrder) other.sortOrder else otherIndex
                val bOrder = if (a.sortOrder != other.sortOrder) a.sortOrder else index
                eventTypeRepository.savePreset(a.copy(sortOrder = aOrder, updatedAt = now))
                eventTypeRepository.savePreset(other.copy(sortOrder = bOrder, updatedAt = now))
                syncScheduler.requestImmediateSync()
            }
        }
    }
