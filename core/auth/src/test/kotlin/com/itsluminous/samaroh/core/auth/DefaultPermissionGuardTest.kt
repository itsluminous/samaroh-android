package com.itsluminous.samaroh.core.auth

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.auth.permissions.PermissionMatrix
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.model.BookingPermissions
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.MemberStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/**
 * §3 enforcement matrix: owner passes everything; active members get exactly what was
 * granted; invited/revoked members and signed-out users get nothing.
 */
class DefaultPermissionGuardTest {
    private val now: Instant = Instant.parse("2026-08-25T09:00:00Z")
    private val bizId = "biz-1"
    private val ownerSession = Session(userId = "owner-uid", email = "owner@example.com")
    private val staffSession = Session(userId = "staff-uid", email = "staff@example.com")

    private val sessionFlow = MutableStateFlow<Session?>(null)
    private val membersFlow = MutableStateFlow<List<BusinessMember>>(emptyList())
    private val businessesFlow = MutableStateFlow<List<Business>>(emptyList())

    private val guard =
        DefaultPermissionGuard(
            sessionHolder = FakeSessionHolder(sessionFlow),
            memberRepository = FakeMemberRepository(membersFlow),
            businessRepository = FakeBusinessRepository(businessesFlow),
        )

    private fun business(ownerUserId: String = ownerSession.userId): Business =
        Business(
            id = bizId,
            name = "Test Hall",
            ownerName = "Owner",
            ownerUserId = ownerUserId,
            createdAt = now,
            updatedAt = now,
        )

    private fun member(
        session: Session,
        status: MemberStatus,
        permissions: MemberPermissions = MemberPermissions(),
        isOwner: Boolean = false,
    ): BusinessMember =
        BusinessMember(
            id = "member-${session.userId}",
            businessId = bizId,
            invitedEmail = session.email,
            userId = session.userId,
            displayName = "Member",
            isOwner = isOwner,
            status = status,
            permissions = permissions,
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun `owner passes all checks including settings`() =
        runTest {
            sessionFlow.value = ownerSession
            businessesFlow.value = listOf(business())
            membersFlow.value = listOf(member(ownerSession, MemberStatus.ACTIVE, isOwner = true))

            guard.permissions(bizId).test {
                assertThat(awaitItem()).isEqualTo(PermissionMatrix.fullAccess())
            }
            guard.isOwner(bizId).test {
                assertThat(awaitItem()).isTrue()
            }
        }

    @Test
    fun `owner by business row passes even without a member row`() =
        runTest {
            sessionFlow.value = ownerSession
            businessesFlow.value = listOf(business())
            membersFlow.value = emptyList()

            guard.permissions(bizId).test {
                assertThat(awaitItem()).isEqualTo(PermissionMatrix.fullAccess())
            }
        }

    @Test
    fun `active member gets exactly the granted permissions`() =
        runTest {
            val granted = MemberPermissions(booking = BookingPermissions(view = true, recordPayment = true))
            sessionFlow.value = staffSession
            businessesFlow.value = listOf(business())
            membersFlow.value = listOf(member(staffSession, MemberStatus.ACTIVE, permissions = granted))

            guard.permissions(bizId).test {
                val perms = awaitItem()
                assertThat(perms.booking.view).isTrue()
                assertThat(perms.booking.recordPayment).isTrue()
                // Denied actions stay false.
                assertThat(perms.booking.edit).isFalse()
                assertThat(perms.booking.delete).isFalse()
                assertThat(perms.expenses.view).isFalse()
                assertThat(perms.settings.manageMembers).isFalse()
            }
            guard.isOwner(bizId).test {
                assertThat(awaitItem()).isFalse()
            }
        }

    @Test
    fun `member view_amounts flows through the guard - off masks, absent stays on`() =
        runTest {
            // ADR-039: owner turned booking amounts off for this member; the other
            // modules never carried the key (absent = TRUE).
            val granted =
                MemberPermissions(booking = BookingPermissions(view = true, viewAmounts = false))
            sessionFlow.value = staffSession
            businessesFlow.value = listOf(business())
            membersFlow.value = listOf(member(staffSession, MemberStatus.ACTIVE, permissions = granted))

            guard.permissions(bizId).test {
                val perms = awaitItem()
                assertThat(perms.booking.viewAmounts).isFalse()
                assertThat(perms.expenses.viewAmounts).isTrue()
                assertThat(perms.inventory.viewAmounts).isTrue()
                assertThat(perms.reports.viewAmounts).isTrue()
            }
        }

    @Test
    fun `owner keeps view_amounts true even when a member row says otherwise`() =
        runTest {
            // Owners bypass the stored object entirely (fullAccess) — no self-lockout.
            val restricted =
                MemberPermissions(booking = BookingPermissions(view = true, viewAmounts = false))
            sessionFlow.value = ownerSession
            businessesFlow.value = listOf(business())
            membersFlow.value = listOf(member(ownerSession, MemberStatus.ACTIVE, permissions = restricted, isOwner = true))

            guard.permissions(bizId).test {
                assertThat(awaitItem().booking.viewAmounts).isTrue()
            }
        }

    @Test
    fun `revoked member loses everything even when permissions json still has grants`() =
        runTest {
            sessionFlow.value = staffSession
            businessesFlow.value = listOf(business())
            membersFlow.value =
                listOf(member(staffSession, MemberStatus.REVOKED, permissions = MemberPermissions.manager()))

            guard.permissions(bizId).test {
                assertThat(awaitItem()).isEqualTo(MemberPermissions())
            }
        }

    @Test
    fun `invited (not yet active) member has no effective permissions`() =
        runTest {
            sessionFlow.value = staffSession
            businessesFlow.value = listOf(business())
            membersFlow.value =
                listOf(member(staffSession, MemberStatus.INVITED, permissions = MemberPermissions.viewer()))

            guard.permissions(bizId).test {
                assertThat(awaitItem()).isEqualTo(MemberPermissions())
            }
        }

    @Test
    fun `signed out user has no permissions and is not owner`() =
        runTest {
            sessionFlow.value = null
            businessesFlow.value = listOf(business())
            membersFlow.value = listOf(member(staffSession, MemberStatus.ACTIVE, MemberPermissions.manager()))

            guard.permissions(bizId).test {
                assertThat(awaitItem()).isEqualTo(MemberPermissions())
            }
            guard.isOwner(bizId).test {
                assertThat(awaitItem()).isFalse()
            }
        }

    @Test
    fun `revocation while observing takes effect on the flow`() =
        runTest {
            sessionFlow.value = staffSession
            businessesFlow.value = listOf(business())
            membersFlow.value = listOf(member(staffSession, MemberStatus.ACTIVE, MemberPermissions.viewer()))

            guard.permissions(bizId).test {
                assertThat(awaitItem().booking.view).isTrue()
                membersFlow.value = listOf(member(staffSession, MemberStatus.REVOKED, MemberPermissions.viewer()))
                assertThat(awaitItem()).isEqualTo(MemberPermissions())
            }
        }
}

private class FakeSessionHolder(
    private val flow: MutableStateFlow<Session?>,
) : SessionHolder {
    override val session: Flow<Session?> = flow

    override suspend fun signOut() {
        flow.value = null
    }
}

private class FakeMemberRepository(
    private val members: MutableStateFlow<List<BusinessMember>>,
) : MemberRepository {
    override fun membersForBusiness(businessId: String): Flow<List<BusinessMember>> =
        members.map { list -> list.filter { it.businessId == businessId } }

    override suspend fun memberForUser(
        businessId: String,
        userId: String,
    ): BusinessMember? = members.value.firstOrNull { it.businessId == businessId && it.userId == userId }

    override suspend fun saveMember(member: BusinessMember) {
        members.value = members.value.filterNot { it.id == member.id } + member
    }
}

private class FakeBusinessRepository(
    private val list: MutableStateFlow<List<Business>>,
) : BusinessRepository {
    override fun businesses(): Flow<List<Business>> = list

    override suspend fun business(id: String): Business? = list.value.firstOrNull { it.id == id }

    override suspend fun saveBusiness(business: Business) {
        list.value = list.value.filterNot { it.id == business.id } + business
    }

    override fun settings(businessId: String): Flow<BusinessSettings?> = MutableStateFlow(null)

    override suspend fun saveSettings(settings: BusinessSettings) = Unit
}
