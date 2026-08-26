package com.itsluminous.samaroh.feature.menu.fakes

import android.content.Context
import android.content.Intent
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.auth.Session
import com.itsluminous.samaroh.core.auth.SessionHolder
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.core.model.MemberPermissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeBusinessRepository(
    initialBusinesses: List<Business> = emptyList(),
    initialSettings: Map<String, BusinessSettings> = emptyMap(),
) : BusinessRepository {
    val businessesFlow = MutableStateFlow(initialBusinesses)
    val settingsFlow = MutableStateFlow(initialSettings)

    override fun businesses(): Flow<List<Business>> = businessesFlow

    override suspend fun business(id: String): Business? = businessesFlow.value.firstOrNull { it.id == id }

    override suspend fun saveBusiness(business: Business) {
        businessesFlow.value = businessesFlow.value.filterNot { it.id == business.id } + business
    }

    override fun settings(businessId: String): Flow<BusinessSettings?> = settingsFlow.map { it[businessId] }

    override suspend fun saveSettings(settings: BusinessSettings) {
        settingsFlow.value = settingsFlow.value + (settings.businessId to settings)
    }
}

class FakeMemberRepository : MemberRepository {
    val membersFlow = MutableStateFlow<List<BusinessMember>>(emptyList())

    override fun membersForBusiness(businessId: String): Flow<List<BusinessMember>> =
        membersFlow.map { list -> list.filter { it.businessId == businessId } }

    override suspend fun memberForUser(
        businessId: String,
        userId: String,
    ): BusinessMember? = membersFlow.value.firstOrNull { it.businessId == businessId && it.userId == userId }

    override suspend fun saveMember(member: BusinessMember) {
        membersFlow.value = membersFlow.value.filterNot { it.id == member.id } + member
    }
}

class FakePermissionGuard(
    val ownerFlow: MutableStateFlow<Boolean> = MutableStateFlow(false),
    val permissionsFlow: MutableStateFlow<MemberPermissions> = MutableStateFlow(MemberPermissions()),
) : PermissionGuard {
    override fun permissions(businessId: String): Flow<MemberPermissions> = permissionsFlow

    override fun isOwner(businessId: String): Flow<Boolean> = ownerFlow
}

class FakeSessionHolder(
    val sessionFlow: MutableStateFlow<Session?> = MutableStateFlow(null),
) : SessionHolder {
    override val session: Flow<Session?> = sessionFlow

    override suspend fun signOut() {
        sessionFlow.value = null
    }
}

class FakeGoogleAccountLinker(
    val state: MutableStateFlow<GoogleLinkState> = MutableStateFlow(GoogleLinkState.NotLinked),
    var linkResult: Result<GoogleLinkState.Linked>? = null,
) : GoogleAccountLinker {
    var linkCalls = 0
    var unlinkCalls = 0

    override val linkState: Flow<GoogleLinkState> = state

    override suspend fun link(activityContext: Context): Result<GoogleLinkState.Linked> {
        linkCalls++
        return resolveLink()
    }

    override suspend fun completeLink(resultIntent: Intent?): Result<GoogleLinkState.Linked> = resolveLink()

    override suspend fun unlink() {
        unlinkCalls++
        state.value = GoogleLinkState.NotLinked
    }

    private fun resolveLink(): Result<GoogleLinkState.Linked> {
        val result = linkResult ?: Result.success(GoogleLinkState.Linked("owner@example.com", emptyList()))
        result.onSuccess { state.value = it }
        return result
    }
}

/** Adapter: wraps a [BusinessRepository] as the ADR-017 active-business session source. */
class FakeActiveBusinessProvider(
    repository: BusinessRepository,
) : com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider {
    override val activeBusiness: Flow<Business?> =
        repository.businesses().map { list -> list.firstOrNull { it.deletedAt == null } }
}
