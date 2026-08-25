package com.itsluminous.samaroh.feature.menu.ui.members

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.MemberStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.menu.data.CurrentBusinessProvider
import com.itsluminous.samaroh.feature.menu.fakes.FakeBusinessRepository
import com.itsluminous.samaroh.feature.menu.fakes.FakeMemberRepository
import com.itsluminous.samaroh.feature.menu.fakes.FakePermissionGuard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class MembersViewModelTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2026-08-25T09:00:00Z"), ZoneOffset.UTC)

    private lateinit var memberRepository: FakeMemberRepository
    private lateinit var permissionGuard: FakePermissionGuard
    private lateinit var viewModel: MembersViewModel

    private val existingMember =
        BusinessMember(
            id = "m-1",
            businessId = Fixtures.BUSINESS_ID,
            invitedEmail = "staff@example.com",
            displayName = "staff-member",
            status = MemberStatus.ACTIVE,
            permissions = MemberPermissions.staff(),
            createdAt = Fixtures.NOW,
            updatedAt = Fixtures.NOW,
        )

    @Before
    fun setUp() {
        memberRepository = FakeMemberRepository()
        permissionGuard = FakePermissionGuard()
        viewModel =
            MembersViewModel(
                currentBusinessProvider =
                    CurrentBusinessProvider(FakeBusinessRepository(initialBusinesses = listOf(Fixtures.business()))),
                memberRepository = memberRepository,
                permissionGuard = permissionGuard,
                clock = clock,
            )
    }

    @Test
    fun `non-owners never see the member list`() =
        runTest(dispatcherRule.dispatcher) {
            memberRepository.membersFlow.value = listOf(existingMember)
            permissionGuard.ownerFlow.value = false

            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()

            assertThat(viewModel.uiState.value.isOwner).isFalse()
            assertThat(viewModel.uiState.value.members).isEmpty()
            collector.cancel()
        }

    @Test
    fun `owners see members and owner gating flips live`() =
        runTest(dispatcherRule.dispatcher) {
            memberRepository.membersFlow.value = listOf(existingMember)
            permissionGuard.ownerFlow.value = true

            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()

            assertThat(viewModel.uiState.value.isOwner).isTrue()
            assertThat(viewModel.uiState.value.members).containsExactly(existingMember)

            permissionGuard.ownerFlow.value = false
            runCurrent()
            assertThat(viewModel.uiState.value.members).isEmpty()
            collector.cancel()
        }

    @Test
    fun `addMember creates an INVITED viewer with email and display name`() =
        runTest(dispatcherRule.dispatcher) {
            permissionGuard.ownerFlow.value = true
            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()

            viewModel.addMember(email = "  new@example.com ", displayName = " New Member ")
            runCurrent()

            val added = memberRepository.membersFlow.value.single()
            assertThat(added.invitedEmail).isEqualTo("new@example.com")
            assertThat(added.displayName).isEqualTo("New Member")
            assertThat(added.status).isEqualTo(MemberStatus.INVITED)
            assertThat(added.permissions).isEqualTo(MemberPermissions.viewer())
            assertThat(added.businessId).isEqualTo(Fixtures.BUSINESS_ID)
            collector.cancel()
        }

    @Test
    fun `addMember is refused for non-owners and invalid input`() =
        runTest(dispatcherRule.dispatcher) {
            permissionGuard.ownerFlow.value = false
            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()

            viewModel.addMember("new@example.com", "New Member")
            runCurrent()
            assertThat(memberRepository.membersFlow.value).isEmpty()

            permissionGuard.ownerFlow.value = true
            runCurrent()
            viewModel.addMember("not-an-email", "Someone")
            viewModel.addMember("a@b.com", "   ")
            runCurrent()
            assertThat(memberRepository.membersFlow.value).isEmpty()
            collector.cancel()
        }

    @Test
    fun `revokeMember tombstones access but never the owner`() =
        runTest(dispatcherRule.dispatcher) {
            val ownerRow = existingMember.copy(id = "m-owner", isOwner = true)
            memberRepository.membersFlow.value = listOf(existingMember, ownerRow)
            permissionGuard.ownerFlow.value = true
            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()

            viewModel.revokeMember(existingMember)
            viewModel.revokeMember(ownerRow)
            runCurrent()

            val byId = memberRepository.membersFlow.value.associateBy { it.id }
            assertThat(byId.getValue("m-1").status).isEqualTo(MemberStatus.REVOKED)
            assertThat(byId.getValue("m-owner").status).isEqualTo(MemberStatus.ACTIVE)
            collector.cancel()
        }
}
