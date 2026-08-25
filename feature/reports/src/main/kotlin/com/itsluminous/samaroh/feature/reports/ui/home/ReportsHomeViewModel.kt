package com.itsluminous.samaroh.feature.reports.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Reports home state: the whole section is gated behind `reports.view` (§3, §4.4). */
data class ReportsHomeUiState(
    val loading: Boolean = true,
    val canView: Boolean = false,
)

@HiltViewModel
class ReportsHomeViewModel
    @Inject
    constructor(
        activeBusinessProvider: ActiveBusinessProvider,
        currentUserProvider: CurrentUserProvider,
        permissionGuard: PermissionGuard,
    ) : ViewModel() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<ReportsHomeUiState> =
            combine(
                activeBusinessProvider.activeBusiness,
                currentUserProvider.currentUserId,
            ) { business, userId -> business to userId }
                .flatMapLatest { (business, userId) ->
                    when {
                        business == null -> flowOf(ReportsHomeUiState(loading = false, canView = false))
                        // Signed-out/offline: owner-mode default on the local business (ADR-017, §3).
                        userId == null -> flowOf(ReportsHomeUiState(loading = false, canView = true))
                        else ->
                            permissionGuard
                                .permissions(business.id)
                                .map { ReportsHomeUiState(loading = false, canView = it.reports.view) }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsHomeUiState())
    }
