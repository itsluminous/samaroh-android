package com.itsluminous.samaroh.feature.expenses.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.settings.ListSortOrder
import com.itsluminous.samaroh.core.data.settings.ListSortPreferences
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.expenses.FakeExpensesLedgerRepository
import com.itsluminous.samaroh.feature.expenses.FakeExpensesRepository
import com.itsluminous.samaroh.feature.expenses.fakeExpensesSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class ExpensesHomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(mainDispatcherRule.dispatcher + Job())
    private val sortPreferences by lazy {
        ListSortPreferences(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tmp.root, "settings.preferences_pb")
            },
        )
    }

    private lateinit var expensesRepository: FakeExpensesRepository
    private lateinit var ledgerRepository: FakeExpensesLedgerRepository

    @Before
    fun setUp() {
        expensesRepository = FakeExpensesRepository()
        ledgerRepository = FakeExpensesLedgerRepository()
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private fun viewModel() = ExpensesHomeViewModel(expensesRepository, ledgerRepository, fakeExpensesSession(), sortPreferences)

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

    @Test
    fun `owner keeps the add-person gate open`() =
        runTest {
            val viewModel =
                ExpensesHomeViewModel(expensesRepository, ledgerRepository, fakeExpensesSession(), sortPreferences)
            viewModel.state.test {
                assertThat(awaitItemMatching { it.canManageParties }.canManageParties).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `viewer without manage_parties cannot add people`() =
        runTest {
            expensesRepository.parties.value = listOf(Fixtures.party(name = "Ramesh Kumar"))
            val session =
                fakeExpensesSession(
                    userId = "viewer-1",
                    isOwner = false,
                    permissions =
                        com.itsluminous.samaroh.core.model
                            .MemberPermissions(),
                )
            val viewModel = ExpensesHomeViewModel(expensesRepository, ledgerRepository, session, sortPreferences)
            viewModel.state.test {
                val state = awaitItemMatching { it.hasAnyParty }
                assertThat(state.canManageParties).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `member without explicit view_amounts keeps amounts visible (absent = true)`() =
        runTest {
            expensesRepository.parties.value = listOf(Fixtures.party(name = "Ramesh Kumar"))
            val session =
                fakeExpensesSession(
                    userId = "viewer-1",
                    isOwner = false,
                    permissions =
                        com.itsluminous.samaroh.core.model
                            .MemberPermissions(),
                )
            val viewModel = ExpensesHomeViewModel(expensesRepository, ledgerRepository, session, sortPreferences)
            viewModel.state.test {
                assertThat(awaitItemMatching { it.hasAnyParty }.canViewAmounts).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `member with expenses view_amounts off gets the masked state`() =
        runTest {
            expensesRepository.parties.value = listOf(Fixtures.party(name = "Ramesh Kumar"))
            val session =
                fakeExpensesSession(
                    userId = "viewer-1",
                    isOwner = false,
                    permissions =
                        com.itsluminous.samaroh.core.model.MemberPermissions(
                            expenses =
                                com.itsluminous.samaroh.core.model
                                    .ExpensesPermissions(view = true, viewAmounts = false),
                        ),
                )
            val viewModel = ExpensesHomeViewModel(expensesRepository, ledgerRepository, session, sortPreferences)
            viewModel.state.test {
                assertThat(awaitItemMatching { it.hasAnyParty }.canViewAmounts).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `default last-updated sorts most recent entry first with entry-less parties last`() =
        runTest {
            val amit = Fixtures.party(name = "Amit Traders")
            val priya = Fixtures.party(name = "Priya Caterers")
            val ramesh = Fixtures.party(name = "Ramesh Kumar")
            expensesRepository.parties.value = listOf(amit, priya, ramesh)
            val entries =
                listOf(
                    Fixtures.expense(partyId = priya.id).copy(createdAt = Instant.parse("2026-09-05T10:00:00Z")),
                    Fixtures.expense(partyId = amit.id).copy(createdAt = Instant.parse("2026-09-01T10:00:00Z")),
                    // ramesh has no entries — he sinks to the end despite the "R" name.
                )
            expensesRepository.expenses.value = entries
            ledgerRepository.expenses.value = entries

            viewModel().state.test {
                val state = awaitItemMatching { it.parties.size == 3 }
                assertThat(state.sortOrder).isEqualTo(ListSortOrder.LAST_UPDATED)
                assertThat(state.parties.map { it.party.name })
                    .containsExactly("Priya Caterers", "Amit Traders", "Ramesh Kumar")
                    .inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `name ascending and descending reorder the party list`() =
        runTest {
            expensesRepository.parties.value =
                listOf(
                    Fixtures.party(name = "Ramesh Kumar"),
                    Fixtures.party(name = "amit traders"),
                    Fixtures.party(name = "Priya Caterers"),
                )

            val viewModel = viewModel()
            viewModel.state.test {
                awaitItemMatching { it.parties.size == 3 }

                viewModel.onSortOrderChange(ListSortOrder.NAME_ASC)
                val ascending = awaitItemMatching { it.sortOrder == ListSortOrder.NAME_ASC }
                // Case-insensitive: lowercase "amit traders" still leads.
                assertThat(ascending.parties.map { it.party.name })
                    .containsExactly("amit traders", "Priya Caterers", "Ramesh Kumar")
                    .inOrder()

                viewModel.onSortOrderChange(ListSortOrder.NAME_DESC)
                val descending = awaitItemMatching { it.sortOrder == ListSortOrder.NAME_DESC }
                assertThat(descending.parties.map { it.party.name })
                    .containsExactly("Ramesh Kumar", "Priya Caterers", "amit traders")
                    .inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `search filters first, then the chosen sort applies within the results`() =
        runTest {
            expensesRepository.parties.value =
                listOf(
                    Fixtures.party(name = "Ramesh Kumar"),
                    Fixtures.party(name = "Ramesh Traders"),
                    Fixtures.party(name = "Priya Caterers"),
                )

            val viewModel = viewModel()
            viewModel.state.test {
                awaitItemMatching { it.parties.size == 3 }
                viewModel.onSortOrderChange(ListSortOrder.NAME_DESC)
                awaitItemMatching { it.sortOrder == ListSortOrder.NAME_DESC }

                viewModel.onSearchQueryChange("ramesh")
                val filtered = awaitItemMatching { it.parties.size == 2 }
                assertThat(filtered.parties.map { it.party.name })
                    .containsExactly("Ramesh Traders", "Ramesh Kumar")
                    .inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `chosen sort persists - a fresh ViewModel over the same prefs restores it`() =
        runTest {
            expensesRepository.parties.value = listOf(Fixtures.party(name = "Ramesh Kumar"))

            val first = viewModel()
            first.state.test {
                awaitItemMatching { it.hasAnyParty }
                first.onSortOrderChange(ListSortOrder.NAME_ASC)
                awaitItemMatching { it.sortOrder == ListSortOrder.NAME_ASC }
                cancelAndIgnoreRemainingEvents()
            }

            viewModel().state.test {
                assertThat(awaitItemMatching { it.hasAnyParty }.sortOrder).isEqualTo(ListSortOrder.NAME_ASC)
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
