package com.itsluminous.samaroh.feature.menu.ui.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.MemberStatus
import com.itsluminous.samaroh.feature.menu.data.CurrentBusinessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/** Members screen state (§4.4, owner-only). */
data class MembersUiState(
    val loading: Boolean = true,
    val businessId: String? = null,
    /** Non-owners get the localized "owner only" message instead of the list (§3). */
    val isOwner: Boolean = false,
    val members: List<BusinessMember> = emptyList(),
)

@HiltViewModel
class MembersViewModel
    @Inject
    constructor(
        currentBusinessProvider: CurrentBusinessProvider,
        private val memberRepository: MemberRepository,
        permissionGuard: PermissionGuard,
        private val clock: Clock,
    ) : ViewModel() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<MembersUiState> =
            currentBusinessProvider.currentBusiness
                .flatMapLatest { business ->
                    if (business == null) {
                        flowOf(MembersUiState(loading = false))
                    } else {
                        combine(
                            permissionGuard.isOwner(business.id),
                            memberRepository.membersForBusiness(business.id),
                        ) { isOwner, members ->
                            MembersUiState(
                                loading = false,
                                businessId = business.id,
                                isOwner = isOwner,
                                // Employees never see the member list (§3 owner-only surface).
                                members = if (isOwner) members else emptyList(),
                            )
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MembersUiState())

        /** Invites a member by email + display name (§4.4); starts with view-only permissions. */
        fun addMember(
            email: String,
            displayName: String,
        ) {
            val state = uiState.value
            val businessId = state.businessId ?: return
            if (!state.isOwner) return
            val trimmedEmail = email.trim()
            val trimmedName = displayName.trim()
            if (trimmedEmail.isBlank() || !trimmedEmail.contains('@') || trimmedName.isBlank()) return
            viewModelScope.launch {
                val now = clock.instant()
                memberRepository.saveMember(
                    BusinessMember(
                        id = UUID.randomUUID().toString(),
                        businessId = businessId,
                        invitedEmail = trimmedEmail,
                        displayName = trimmedName,
                        status = MemberStatus.INVITED,
                        permissions = MemberPermissions.viewer(),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }

        fun revokeMember(member: BusinessMember) {
            if (!uiState.value.isOwner || member.isOwner) return
            viewModelScope.launch {
                memberRepository.saveMember(member.copy(status = MemberStatus.REVOKED, updatedAt = clock.instant()))
            }
        }
    }
