package com.itsluminous.samaroh.feature.expenses.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.settings.ListSortOrder
import com.itsluminous.samaroh.core.data.settings.ListSortPreferences
import com.itsluminous.samaroh.core.data.settings.SummaryPeriod
import com.itsluminous.samaroh.core.data.settings.SummaryPeriodPreferences
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ExpensesHomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(mainDispatcherRule.dispatcher + Job())
    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tmp.root, "settings.preferences_pb")
        }
    }
    private val sortPreferences by lazy { ListSortPreferences(dataStore) }
    private val summaryPreferences by lazy { SummaryPeriodPreferences(dataStore) }

    /**
     * Fixed "now": 2026-08-15T12:00Z — mid-month noon so every zone agrees on the local date
     * (the ADR-091 window is resolved on the DEVICE-local date; zone edges are covered by
     * [com.itsluminous.samaroh.feature.expenses.domain.SummaryPeriodRangeTest]).
     */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-15T12:00:00Z"), ZoneOffset.UTC)

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

    private fun viewModel() =
        ExpensesHomeViewModel(expensesRepository, ledgerRepository, fakeExpensesSession(), sortPreferences, summaryPreferences, clock)

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
                ExpensesHomeViewModel(
                    expensesRepository,
                    ledgerRepository,
                    fakeExpensesSession(),
                    sortPreferences,
                    summaryPreferences,
                    clock,
                )
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
            val viewModel = ExpensesHomeViewModel(expensesRepository, ledgerRepository, session, sortPreferences, summaryPreferences, clock)
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
            val viewModel = ExpensesHomeViewModel(expensesRepository, ledgerRepository, session, sortPreferences, summaryPreferences, clock)
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
            val viewModel = ExpensesHomeViewModel(expensesRepository, ledgerRepository, session, sortPreferences, summaryPreferences, clock)
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

    // ---- ADR-091: summary period ----

    /** One party with entries dated in the fixed clock's month, earlier in its year, and in the prior year. */
    private fun seedPeriodEntries() {
        val party = Fixtures.party(name = "Period Party")
        expensesRepository.parties.value = listOf(party)
        val entries =
            listOf(
                // This month (Aug 2026): first and last day, both directions.
                Fixtures.expense(partyId = party.id, amountPaise = 100_00L, expenseDate = LocalDate.of(2026, 8, 1)),
                Fixtures.expense(partyId = party.id, amountPaise = 200_00L, expenseDate = LocalDate.of(2026, 8, 31)),
                Fixtures.expense(
                    partyId = party.id,
                    amountPaise = 50_00L,
                    direction = ExpenseDirection.RECEIVED,
                    expenseDate = LocalDate.of(2026, 8, 15),
                ),
                // This year, outside this month: 1 Jan and 31 Dec 2026 (the year's edges).
                Fixtures.expense(partyId = party.id, amountPaise = 1_000_00L, expenseDate = LocalDate.of(2026, 1, 1)),
                Fixtures.expense(partyId = party.id, amountPaise = 2_000_00L, expenseDate = LocalDate.of(2026, 12, 31)),
                // Prior year and the day just before/after the month — all-time only.
                Fixtures.expense(partyId = party.id, amountPaise = 10_000_00L, expenseDate = LocalDate.of(2025, 12, 31)),
                Fixtures.expense(
                    partyId = party.id,
                    amountPaise = 7_00L,
                    direction = ExpenseDirection.RECEIVED,
                    expenseDate = LocalDate.of(2026, 7, 31),
                ),
                Fixtures.expense(
                    partyId = party.id,
                    amountPaise = 9_00L,
                    direction = ExpenseDirection.RECEIVED,
                    expenseDate = LocalDate.of(2026, 9, 1),
                ),
            )
        expensesRepository.expenses.value = entries
        ledgerRepository.expenses.value = entries
    }

    @Test
    fun `summary defaults to this month and totals only entries dated in the month`() =
        runTest {
            seedPeriodEntries()
            viewModel().state.test {
                val state = awaitItemMatching { it.hasAnyParty }
                assertThat(state.summaryPeriod).isEqualTo(SummaryPeriod.THIS_MONTH)
                assertThat(state.totals.gavePaise).isEqualTo(300_00L) // 1 Aug + 31 Aug, inclusive edges
                assertThat(state.totals.gotPaise).isEqualTo(50_00L) // 31 Jul and 1 Sep excluded
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `this year spans 1 Jan to 31 Dec inclusive and all time drops the bounds`() =
        runTest {
            seedPeriodEntries()
            val viewModel = viewModel()
            viewModel.state.test {
                awaitItemMatching { it.hasAnyParty }

                viewModel.onSummaryPeriodChange(SummaryPeriod.THIS_YEAR)
                val year = awaitItemMatching { it.summaryPeriod == SummaryPeriod.THIS_YEAR }
                assertThat(year.totals.gavePaise).isEqualTo(300_00L + 1_000_00L + 2_000_00L)
                assertThat(year.totals.gotPaise).isEqualTo(50_00L + 7_00L + 9_00L)

                viewModel.onSummaryPeriodChange(SummaryPeriod.ALL_TIME)
                val all = awaitItemMatching { it.summaryPeriod == SummaryPeriod.ALL_TIME }
                assertThat(all.totals.gavePaise).isEqualTo(300_00L + 1_000_00L + 2_000_00L + 10_000_00L)
                assertThat(all.totals.gotPaise).isEqualTo(50_00L + 7_00L + 9_00L)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `chosen summary period persists - a fresh ViewModel over the same prefs restores it`() =
        runTest {
            seedPeriodEntries()
            val first = viewModel()
            first.state.test {
                awaitItemMatching { it.hasAnyParty }
                first.onSummaryPeriodChange(SummaryPeriod.ALL_TIME)
                awaitItemMatching { it.summaryPeriod == SummaryPeriod.ALL_TIME }
                cancelAndIgnoreRemainingEvents()
            }

            viewModel().state.test {
                val restored = awaitItemMatching { it.hasAnyParty }
                assertThat(restored.summaryPeriod).isEqualTo(SummaryPeriod.ALL_TIME)
                assertThat(restored.totals.gavePaise).isEqualTo(300_00L + 1_000_00L + 2_000_00L + 10_000_00L)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `period switch keeps the view_amounts mask state untouched`() =
        runTest {
            seedPeriodEntries()
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
            val viewModel = ExpensesHomeViewModel(expensesRepository, ledgerRepository, session, sortPreferences, summaryPreferences, clock)
            viewModel.state.test {
                assertThat(awaitItemMatching { it.hasAnyParty }.canViewAmounts).isFalse()
                viewModel.onSummaryPeriodChange(SummaryPeriod.THIS_YEAR)
                assertThat(awaitItemMatching { it.summaryPeriod == SummaryPeriod.THIS_YEAR }.canViewAmounts).isFalse()
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
