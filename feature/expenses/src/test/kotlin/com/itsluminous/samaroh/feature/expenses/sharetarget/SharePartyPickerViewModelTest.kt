package com.itsluminous.samaroh.feature.expenses.sharetarget

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.model.ExpensesPermissions
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.expenses.FakeExpensesRepository
import com.itsluminous.samaroh.feature.expenses.fakeExpensesSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Create-invoice party picker gates + type-ahead (ADR-078). */
class SharePartyPickerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeExpensesRepository()

    private fun currentUser(userId: String?): CurrentUserProvider =
        object : CurrentUserProvider {
            override val currentUserId = MutableStateFlow(userId)
        }

    private fun viewModel(
        userId: String?,
        isOwner: Boolean = false,
        permissions: MemberPermissions = MemberPermissions(),
    ) = SharePartyPickerViewModel(
        expensesRepository = repository,
        session = fakeExpensesSession(userId = userId, isOwner = isOwner, permissions = permissions),
        currentUserProvider = currentUser(userId),
        holder = ShareTargetHolder(),
    )

    @Test
    fun `signed-out shares get the graceful message`() =
        runTest {
            viewModel(userId = null).state.test {
                assertThat(awaitItemMatching { it.gate != ShareTargetGate.LOADING }.gate)
                    .isEqualTo(ShareTargetGate.NOT_SIGNED_IN)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `members without expenses create get the permission message`() =
        runTest {
            val vm =
                viewModel(
                    userId = "member-1",
                    permissions = MemberPermissions(expenses = ExpensesPermissions(view = true)),
                )
            vm.state.test {
                assertThat(awaitItemMatching { it.gate != ShareTargetGate.LOADING }.gate)
                    .isEqualTo(ShareTargetGate.NO_PERMISSION)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `creators see the party list and the type-ahead filters it`() =
        runTest {
            repository.parties.value =
                listOf(
                    Fixtures.party(id = "p-1", name = "Sharma Caterers"),
                    Fixtures.party(id = "p-2", name = "Verma Decorators"),
                )
            val vm =
                viewModel(
                    userId = "member-1",
                    permissions = MemberPermissions(expenses = ExpensesPermissions(view = true, create = true)),
                )
            vm.state.test {
                val ready = awaitItemMatching { it.gate == ShareTargetGate.READY && it.parties.isNotEmpty() }
                assertThat(ready.parties.map { it.id }).containsExactly("p-1", "p-2")
                assertThat(ready.hasAnyParty).isTrue()

                vm.onQueryChange("sharma")
                val filtered = awaitItemMatching { it.parties.size == 1 }
                assertThat(filtered.parties.single().id).isEqualTo("p-1")
                assertThat(filtered.hasAnyParty).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `owners pass the create gate implicitly`() =
        runTest {
            viewModel(userId = Fixtures.USER_ID, isOwner = true).state.test {
                assertThat(awaitItemMatching { it.gate != ShareTargetGate.LOADING }.gate)
                    .isEqualTo(ShareTargetGate.READY)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun <T> app.cash.turbine.TurbineTestContext<T>.awaitItemMatching(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
