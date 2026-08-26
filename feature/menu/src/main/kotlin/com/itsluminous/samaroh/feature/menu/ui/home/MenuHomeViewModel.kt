package com.itsluminous.samaroh.feature.menu.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.auth.SessionHolder
import com.itsluminous.samaroh.feature.menu.data.CurrentBusinessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Menu tab home state — the Members row is owner-only (§4.4); the identity row shows
 * the signed-in email or a localized "not signed in" state in offline/no-account mode.
 */
data class MenuHomeUiState(
    val isOwner: Boolean = false,
    val signedInEmail: String? = null,
)

@HiltViewModel
class MenuHomeViewModel
    @Inject
    constructor(
        currentBusinessProvider: CurrentBusinessProvider,
        permissionGuard: PermissionGuard,
        sessionHolder: SessionHolder,
    ) : ViewModel() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<MenuHomeUiState> =
            combine(
                currentBusinessProvider.currentBusiness.flatMapLatest { business ->
                    if (business == null) flowOf(false) else permissionGuard.isOwner(business.id)
                },
                sessionHolder.session,
            ) { isOwner, session ->
                MenuHomeUiState(isOwner = isOwner, signedInEmail = session?.email)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MenuHomeUiState())
    }
