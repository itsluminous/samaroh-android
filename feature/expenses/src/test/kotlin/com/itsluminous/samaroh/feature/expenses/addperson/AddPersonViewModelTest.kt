package com.itsluminous.samaroh.feature.expenses.addperson

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.expenses.FakeExpensesRepository
import com.itsluminous.samaroh.feature.expenses.fakeExpensesSession
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset

class AddPersonViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: FakeExpensesRepository
    private lateinit var viewModel: AddPersonViewModel

    @Before
    fun setUp() {
        repository = FakeExpensesRepository()
        viewModel = AddPersonViewModel(repository, fakeExpensesSession(), Clock.fixed(Fixtures.NOW, ZoneOffset.UTC))
    }

    @Test
    fun `debounced query surfaces fuzzy suggestions`() =
        runTest {
            val ramesh = Fixtures.party(name = "Ramesh Kumar")
            val priya = Fixtures.party(name = "Priya Caterers")
            repository.parties.value = listOf(ramesh, priya)

            viewModel.onQueryDebounced("Ramesh")

            assertThat(
                viewModel.state.value.suggestions
                    .map { it.name },
            ).containsExactly("Ramesh Kumar")
        }

    @Test
    fun `selecting a suggestion steers to the existing party`() =
        runTest {
            val ramesh = Fixtures.party(name = "Ramesh Kumar")
            repository.parties.value = listOf(ramesh)

            viewModel.events.test {
                viewModel.onSuggestionSelected("Ramesh Kumar")
                val event = awaitItem()
                assertThat(event).isInstanceOf(AddPersonEvent.SteeredToExisting::class.java)
                assertThat((event as AddPersonEvent.SteeredToExisting).partyId).isEqualTo(ramesh.id)
            }
            assertThat(repository.savedParties).isEmpty()
        }

    @Test
    fun `saving an exact duplicate name steers instead of creating`() =
        runTest {
            val existing = Fixtures.party(name = "Ramesh Kumar")
            repository.parties.value = listOf(existing)
            viewModel.onNameChange("  ramesh   kumar ")

            viewModel.events.test {
                viewModel.save()
                val event = awaitItem()
                assertThat(event).isInstanceOf(AddPersonEvent.SteeredToExisting::class.java)
                assertThat((event as AddPersonEvent.SteeredToExisting).partyId).isEqualTo(existing.id)
            }
            assertThat(repository.savedParties).isEmpty()
        }

    @Test
    fun `saving a new name creates the party and emits Created`() =
        runTest {
            viewModel.onNameChange("Suresh Traders")
            viewModel.onPhoneChange("9876543210")

            viewModel.events.test {
                viewModel.save()
                val event = awaitItem()
                assertThat(event).isInstanceOf(AddPersonEvent.Created::class.java)
            }
            val saved = repository.savedParties.single()
            assertThat(saved.name).isEqualTo("Suresh Traders")
            assertThat(saved.phone).isEqualTo("9876543210")
        }

    @Test
    fun `blank name flags an error and saves nothing`() =
        runTest {
            viewModel.onNameChange("   ")
            viewModel.save()
            assertThat(viewModel.state.value.nameError).isTrue()
            assertThat(repository.savedParties).isEmpty()
        }

    @Test
    fun `contact pick fills empty fields without clobbering a typed name`() =
        runTest {
            viewModel.onNameChange("My Name")
            viewModel.onContactPicked(name = "Contact Name", phone = "12345")
            assertThat(viewModel.state.value.name).isEqualTo("My Name")
            assertThat(viewModel.state.value.phone).isEqualTo("12345")
        }

    @Test
    fun `failed contact pick emits an event`() =
        runTest {
            viewModel.events.test {
                viewModel.onContactPicked(name = null, phone = null)
                assertThat(awaitItem()).isEqualTo(AddPersonEvent.ContactPickFailed)
            }
        }
}
