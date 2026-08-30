package com.itsluminous.samaroh.feature.menu.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.auth.SessionHolder
import com.itsluminous.samaroh.core.data.session.SignOutCleaner
import com.itsluminous.samaroh.core.data.sync.SyncStatus
import com.itsluminous.samaroh.feature.menu.data.CurrentBusinessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Menu tab home state — the Members row is owner-only (§4.4); the identity row shows
 * the signed-in email or a localized "not signed in" state in offline/no-account mode.
 * Signed in, the row carries a sign-out icon (ADR-040): the confirmation dialog warns
 * with [pendingSyncCount] when unsynced outbox changes would be lost.
 */
data class MenuHomeUiState(
    val isOwner: Boolean = false,
    val signedInEmail: String? = null,
    /** Queued-but-unpushed outbox ops — drives the sign-out dialog's data-loss warning. */
    val pendingSyncCount: Int = 0,
    val showSignOutDialog: Boolean = false,
    /** True while the sign-out + local wipe runs — the dialog's buttons disable. */
    val isSigningOut: Boolean = false,
)

/** One-shot navigation events of the Menu home screen. */
sealed interface MenuHomeEvent {
    /** Sign-out finished (session dropped + local data wiped) — route to sign-in (ADR-040). */
    data object SignedOut : MenuHomeEvent
}

@HiltViewModel
class MenuHomeViewModel
    @Inject
    constructor(
        currentBusinessProvider: CurrentBusinessProvider,
        permissionGuard: PermissionGuard,
        private val sessionHolder: SessionHolder,
        syncStatus: SyncStatus,
        private val signOutCleaner: SignOutCleaner,
    ) : ViewModel() {
        private val signOutDialog = MutableStateFlow(false)
        private val signingOut = MutableStateFlow(false)

        private val _events = Channel<MenuHomeEvent>(Channel.BUFFERED)
        val events: Flow<MenuHomeEvent> = _events.receiveAsFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<MenuHomeUiState> =
            combine(
                currentBusinessProvider.currentBusiness.flatMapLatest { business ->
                    if (business == null) flowOf(false) else permissionGuard.isOwner(business.id)
                },
                sessionHolder.session,
                syncStatus.pendingCount,
                signOutDialog,
                signingOut,
            ) { isOwner, session, pendingCount, dialogVisible, isSigningOut ->
                MenuHomeUiState(
                    isOwner = isOwner,
                    signedInEmail = session?.email,
                    pendingSyncCount = pendingCount,
                    showSignOutDialog = dialogVisible,
                    isSigningOut = isSigningOut,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MenuHomeUiState())

        /** Sign-out icon tap: always confirm first (destructive — local data is wiped). */
        fun onSignOutRequested() {
            signOutDialog.value = true
        }

        fun onSignOutDismissed() {
            if (!signingOut.value) signOutDialog.value = false
        }

        /**
         * Confirmed sign-out (ADR-040): drop the auth session FIRST (no sync run can
         * re-push what we are about to wipe), then clear all session-scoped local data,
         * then emit [MenuHomeEvent.SignedOut] for the shell to route to sign-in.
         */
        fun onSignOutConfirmed() {
            if (signingOut.value) return
            signingOut.value = true
            viewModelScope.launch {
                try {
                    sessionHolder.signOut()
                    signOutCleaner.clearAll()
                    _events.send(MenuHomeEvent.SignedOut)
                } finally {
                    signOutDialog.value = false
                    signingOut.value = false
                }
            }
        }
    }
