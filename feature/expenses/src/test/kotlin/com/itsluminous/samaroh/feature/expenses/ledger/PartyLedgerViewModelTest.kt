package com.itsluminous.samaroh.feature.expenses.ledger

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.ExpenseAttachment
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
import java.time.LocalDate

class PartyLedgerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val party = Fixtures.party(name = "test-party")
    private lateinit var expensesRepository: FakeExpensesRepository
    private lateinit var ledgerRepository: FakeExpensesLedgerRepository

    @Before
    fun setUp() {
        expensesRepository = FakeExpensesRepository()
        ledgerRepository = FakeExpensesLedgerRepository()
        expensesRepository.parties.value = listOf(party)
        ledgerRepository.parties.value = listOf(party)
    }

    private fun viewModel() =
        PartyLedgerViewModel(
            savedStateHandle = SavedStateHandle(mapOf(ARG_PARTY_ID to party.id)),
            expensesRepository = expensesRepository,
            ledgerRepository = ledgerRepository,
            session = fakeExpensesSession(),
            clock = java.time.Clock.fixed(com.itsluminous.samaroh.core.testing.Fixtures.NOW, java.time.ZoneOffset.UTC),
        )

    @Test
    fun `state carries party, newest-first rows and running balances`() =
        runTest {
            val older =
                Fixtures.expense(
                    partyId = party.id,
                    amountPaise = 1_000_00L,
                    direction = ExpenseDirection.PAID,
                    expenseDate = LocalDate.of(2026, 8, 1),
                )
            val newer =
                Fixtures.expense(
                    partyId = party.id,
                    amountPaise = 400_00L,
                    direction = ExpenseDirection.RECEIVED,
                    expenseDate = LocalDate.of(2026, 8, 20),
                )
            expensesRepository.expenses.value = listOf(older, newer)
            ledgerRepository.expenses.value = listOf(older, newer)

            viewModel().state.test {
                val loaded = awaitItemMatching { it.loaded }
                assertThat(loaded.party).isEqualTo(party)
                assertThat(loaded.rows.map { it.expense.id }).containsExactly(newer.id, older.id).inOrder()
                assertThat(loaded.rows.map { it.balanceAfterPaise }).containsExactly(600_00L, 1_000_00L).inOrder()
                assertThat(loaded.netBalancePaise).isEqualTo(600_00L)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `new entries appear reactively`() =
        runTest {
            viewModel().state.test {
                val empty = awaitItemMatching { it.loaded }
                assertThat(empty.rows).isEmpty()

                val entry = Fixtures.expense(partyId = party.id, amountPaise = 250_00L)
                expensesRepository.expenses.value = listOf(entry)

                val updated = awaitItemMatching { it.rows.isNotEmpty() }
                assertThat(
                    updated.rows
                        .single()
                        .expense.id,
                ).isEqualTo(entry.id)
                assertThat(updated.netBalancePaise).isEqualTo(250_00L)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `attachments are grouped by expense with pending state`() =
        runTest {
            val entry = Fixtures.expense(partyId = party.id)
            expensesRepository.expenses.value = listOf(entry)
            ledgerRepository.expenses.value = listOf(entry)
            ledgerRepository.saveAttachment(
                ExpenseAttachment(
                    id = "att-1",
                    expenseId = entry.id,
                    businessId = Fixtures.BUSINESS_ID,
                    driveFileId = null,
                    mimeType = "image/jpeg",
                    fileName = "bill.jpg",
                    createdAt = Fixtures.NOW,
                ),
                localCachePath = "/data/local/bill.jpg",
            )

            viewModel().state.test {
                val state = awaitItemMatching { it.attachmentsByExpense.isNotEmpty() }
                val attachments = state.attachmentsByExpense.getValue(entry.id)
                assertThat(attachments).hasSize(1)
                assertThat(attachments.single().isPendingUpload).isTrue()
                assertThat(attachments.single().localCachePath).isEqualTo("/data/local/bill.jpg")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deleteEntry delegates to the repository tombstone`() =
        runTest {
            val entry = Fixtures.expense(partyId = party.id)
            expensesRepository.expenses.value = listOf(entry)

            val viewModel = viewModel()
            viewModel.deleteEntry(entry.id)

            assertThat(expensesRepository.deletedExpenseIds).containsExactly(entry.id)
        }

    @Test
    fun `edit-delete gate defaults to allowed until PermissionGuard integration`() =
        runTest {
            viewModel().state.test {
                val first = awaitItem()
                assertThat(first.canEditEntries).isTrue()
                assertThat(first.canCreateEntries).isTrue()
                assertThat(first.canDeleteEntries).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `entry gates follow expenses permissions for non-owners`() =
        runTest {
            val viewModel =
                PartyLedgerViewModel(
                    savedStateHandle = SavedStateHandle(mapOf(ARG_PARTY_ID to party.id)),
                    expensesRepository = expensesRepository,
                    ledgerRepository = ledgerRepository,
                    session =
                        fakeExpensesSession(
                            userId = "member-1",
                            isOwner = false,
                            permissions =
                                com.itsluminous.samaroh.core.model.MemberPermissions(
                                    expenses =
                                        com.itsluminous.samaroh.core.model.ExpensesPermissions(
                                            view = true,
                                            create = true,
                                        ),
                                ),
                        ),
                    clock = java.time.Clock.fixed(Fixtures.NOW, java.time.ZoneOffset.UTC),
                )
            viewModel.state.test {
                val loaded = awaitItemMatching { it.loaded }
                // create shows the You gave/You got buttons; edit/delete stay hidden.
                assertThat(loaded.canCreateEntries).isTrue()
                assertThat(loaded.canEditEntries).isFalse()
                assertThat(loaded.canDeleteEntries).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `view-only member gets no entry write gates`() =
        runTest {
            val viewModel =
                PartyLedgerViewModel(
                    savedStateHandle = SavedStateHandle(mapOf(ARG_PARTY_ID to party.id)),
                    expensesRepository = expensesRepository,
                    ledgerRepository = ledgerRepository,
                    session =
                        fakeExpensesSession(
                            userId = "member-1",
                            isOwner = false,
                            permissions =
                                com.itsluminous.samaroh.core.model.MemberPermissions(
                                    expenses =
                                        com.itsluminous.samaroh.core.model
                                            .ExpensesPermissions(view = true),
                                ),
                        ),
                    clock = java.time.Clock.fixed(Fixtures.NOW, java.time.ZoneOffset.UTC),
                )
            viewModel.state.test {
                val loaded = awaitItemMatching { it.loaded }
                assertThat(loaded.canCreateEntries).isFalse()
                assertThat(loaded.canEditEntries).isFalse()
                assertThat(loaded.canDeleteEntries).isFalse()
                assertThat(loaded.canEditParty).isFalse()
                assertThat(loaded.canDeleteParty).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `party gates follow expenses permissions for non-owners`() =
        runTest {
            val viewModel =
                PartyLedgerViewModel(
                    savedStateHandle = SavedStateHandle(mapOf(ARG_PARTY_ID to party.id)),
                    expensesRepository = expensesRepository,
                    ledgerRepository = ledgerRepository,
                    session =
                        fakeExpensesSession(
                            userId = "member-1",
                            isOwner = false,
                            permissions =
                                com.itsluminous.samaroh.core.model.MemberPermissions(
                                    expenses =
                                        com.itsluminous.samaroh.core.model.ExpensesPermissions(
                                            view = true,
                                            manageParties = true,
                                        ),
                                ),
                        ),
                    clock = java.time.Clock.fixed(Fixtures.NOW, java.time.ZoneOffset.UTC),
                )
            viewModel.state.test {
                val loaded = awaitItemMatching { it.loaded }
                // manage_parties grants party edit without expenses.edit; delete needs expenses.delete.
                assertThat(loaded.canEditParty).isTrue()
                assertThat(loaded.canEditEntries).isFalse()
                assertThat(loaded.canDeleteParty).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `saveParty rejects a blank name`() =
        runTest {
            val viewModel = viewModel()
            viewModel.state.test {
                awaitItemMatching { it.party != null }

                viewModel.saveParty(name = "   ", phone = "", businessRelated = true)

                assertThat(viewModel.editPartyError.value).isEqualTo(EditPartyError.EMPTY_NAME)
                assertThat(expensesRepository.savedParties).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `saveParty rejects a name already used by another party`() =
        runTest {
            val other = Fixtures.party(name = "Ramesh Kumar")
            expensesRepository.parties.value = listOf(party, other)

            val viewModel = viewModel()
            viewModel.state.test {
                awaitItemMatching { it.party != null }

                // Case/spacing-insensitive duplicate of the OTHER party's name.
                viewModel.saveParty(name = "  ramesh   KUMAR ", phone = "", businessRelated = true)

                assertThat(viewModel.editPartyError.value).isEqualTo(EditPartyError.DUPLICATE_NAME)
                assertThat(expensesRepository.savedParties).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `saveParty keeping the party's own name is not a duplicate`() =
        runTest {
            val viewModel = viewModel()
            viewModel.state.test {
                awaitItemMatching { it.party != null }
                viewModel.events.test {
                    viewModel.saveParty(name = party.name, phone = "9876543210", businessRelated = false)

                    assertThat(awaitItem()).isEqualTo(PartyLedgerEvent.PartySaved)
                    assertThat(viewModel.editPartyError.value).isNull()
                    val saved = expensesRepository.savedParties.single()
                    assertThat(saved.name).isEqualTo(party.name)
                    assertThat(saved.phone).isEqualTo("9876543210")
                    assertThat(saved.businessRelated).isFalse()
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `saveParty trims name and phone and updates the party`() =
        runTest {
            val viewModel = viewModel()
            viewModel.state.test {
                awaitItemMatching { it.party != null }
                viewModel.events.test {
                    viewModel.saveParty(name = "  Renamed Party  ", phone = "  ", businessRelated = true)

                    assertThat(awaitItem()).isEqualTo(PartyLedgerEvent.PartySaved)
                    val saved = expensesRepository.savedParties.single()
                    assertThat(saved.id).isEqualTo(party.id)
                    assertThat(saved.name).isEqualTo("Renamed Party")
                    assertThat(saved.phone).isNull()
                    assertThat(saved.updatedAt).isEqualTo(Fixtures.NOW)
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deleteParty cascades via the ledger repository and emits PartyDeleted`() =
        runTest {
            val entry = Fixtures.expense(partyId = party.id)
            expensesRepository.expenses.value = listOf(entry)
            ledgerRepository.expenses.value = listOf(entry)

            val viewModel = viewModel()
            viewModel.state.test {
                awaitItemMatching { it.party != null }
                viewModel.events.test {
                    viewModel.deleteParty()

                    assertThat(awaitItem()).isEqualTo(PartyLedgerEvent.PartyDeleted(party.name))
                    assertThat(ledgerRepository.cascadeDeletedPartyIds).containsExactly(party.id)
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deleteParty removes the returned local cached attachment files`() =
        runTest {
            val cacheFile = java.io.File.createTempFile("party-cascade", ".jpg")
            assertThat(cacheFile.exists()).isTrue()
            val entry = Fixtures.expense(partyId = party.id)
            expensesRepository.expenses.value = listOf(entry)
            ledgerRepository.expenses.value = listOf(entry)
            ledgerRepository.saveAttachment(
                ExpenseAttachment(
                    id = "att-cascade",
                    expenseId = entry.id,
                    businessId = Fixtures.BUSINESS_ID,
                    driveFileId = null,
                    mimeType = "image/jpeg",
                    fileName = "bill.jpg",
                    createdAt = Fixtures.NOW,
                ),
                localCachePath = cacheFile.absolutePath,
            )

            val viewModel = viewModel()
            viewModel.state.test {
                awaitItemMatching { it.party != null }
                viewModel.events.test {
                    viewModel.deleteParty()

                    assertThat(awaitItem()).isEqualTo(PartyLedgerEvent.PartyDeleted(party.name))
                    assertThat(cacheFile.exists()).isFalse()
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun app.cash.turbine.ReceiveTurbine<PartyLedgerState>.awaitItemMatching(
        predicate: (PartyLedgerState) -> Boolean,
    ): PartyLedgerState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
