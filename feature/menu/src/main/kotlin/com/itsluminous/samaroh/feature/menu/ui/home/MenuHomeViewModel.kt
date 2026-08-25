package com.itsluminous.samaroh.feature.menu.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.feature.menu.data.CurrentBusinessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Menu tab home state — the Members row is owner-only (§4.4). */
data class MenuHomeUiState(
    val isOwner: Boolean = false,
)

@HiltViewModel
class MenuHomeViewModel
    @Inject
    constructor(
        currentBusinessProvider: CurrentBusinessProvider,
        permissionGuard: PermissionGuard,
    ) : ViewModel() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<MenuHomeUiState> =
            currentBusinessProvider.currentBusiness
                .flatMapLatest { business ->
                    if (business == null) {
                        flowOf(MenuHomeUiState())
                    } else {
                        permissionGuard.isOwner(business.id).map { MenuHomeUiState(isOwner = it) }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MenuHomeUiState())
    }
