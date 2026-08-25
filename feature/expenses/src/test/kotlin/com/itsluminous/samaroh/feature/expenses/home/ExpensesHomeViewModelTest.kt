package com.itsluminous.samaroh.feature.expenses.home

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.expenses.FakeExpensesLedgerRepository
import com.itsluminous.samaroh.feature.expenses.FakeExpensesRepository
import com.itsluminous.samaroh.feature.expenses.fakeExpensesSession
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ExpensesHomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var expensesRepository: FakeExpensesRepository
    private lateinit var ledgerRepository: FakeExpensesLedgerRepository

    @Before
    fun setUp() {
        expensesRepository = FakeExpensesRepository()
        ledgerRepository = FakeExpensesLedgerRepository()
    }

    private fun viewModel() = ExpensesHomeViewModel(expensesRepository, ledgerRepository, fakeExpensesSession())

    @Test
    fun `totals split by direction and net balances flow through`() =
        runTest {
            val ramesh = Fixtures.party(name = "Ramesh Kumar")
            expensesRepository.parties.value = listOf(ramesh)
            val gave = Fixtures.expense(partyId = ramesh.id, amountPaise = 1_000_00L, direction = ExpenseDirection.PAID)
            val got = Fixtures.expense(partyId = ramesh.id, amountPaise = 400_00L, direction = ExpenseDirection.RECEIVED)
            expensesRepository.expenses.value = listOf(gave, got)
            ledgerRepository.expenses.value = listOf(gave, got)

            viewModel().state.test {
                val state = awaitItemMatching { it.hasAnyParty }
                assertThat(state.totals.gavePaise).isEqualTo(1_000_00L)
                assertThat(state.totals.gotPaise).isEqualTo(400_00L)
                val item = state.parties.single()
                assertThat(item.netBalancePaise).isEqualTo(600_00L)
                assertThat(item.lastEntryAt).isEqualTo(Fixtures.NOW)
                assertThat(item.initials).isEqualTo("RK")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `search filters the list but keeps hasAnyParty`() =
        runTest {
            expensesRepository.parties.value =
                listOf(Fixtures.party(name = "Ramesh Kumar"), Fixtures.party(name = "Priya Caterers"))

            val viewModel = viewModel()
            viewModel.state.test {
                awaitItemMatching { it.parties.size == 2 }

                viewModel.onSearchQueryChange("priya")
                val filtered = awaitItemMatching { it.parties.size == 1 }
                assertThat(
                    filtered.parties
                        .single()
                        .party.name,
                ).isEqualTo("Priya Caterers")
                assertThat(filtered.hasAnyParty).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun app.cash.turbine.ReceiveTurbine<ExpensesHomeState>.awaitItemMatching(
        predicate: (ExpensesHomeState) -> Boolean,
    ): ExpensesHomeState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
