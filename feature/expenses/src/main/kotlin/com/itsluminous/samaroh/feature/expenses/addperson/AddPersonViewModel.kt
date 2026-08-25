package com.itsluminous.samaroh.feature.expenses.addperson

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.feature.expenses.ExpensesSessionDefaults
import com.itsluminous.samaroh.feature.expenses.domain.FuzzyNameMatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

data class AddPersonState(
    val name: String = "",
    val phone: String = "",
    /** Existing parties fuzzy-matched to the typed name — the dedupe dropdown (§4.2). */
    val suggestions: List<Party> = emptyList(),
    val nameError: Boolean = false,
    val saving: Boolean = false,
)

sealed interface AddPersonEvent {
    /** A new party was created — open its ledger. */
    data class Created(
        val partyId: String,
    ) : AddPersonEvent

    /** The name matched an existing party — steer to it instead of duplicating. */
    data class SteeredToExisting(
        val partyId: String,
        val partyName: String,
    ) : AddPersonEvent

    /** The contact picker produced nothing usable. */
    data object ContactPickFailed : AddPersonEvent
}

@HiltViewModel
class AddPersonViewModel
    @Inject
    constructor(
        private val expensesRepository: ExpensesRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val businessId = ExpensesSessionDefaults.BUSINESS_ID

        private val _state = MutableStateFlow(AddPersonState())
        val state: StateFlow<AddPersonState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<AddPersonEvent>(extraBufferCapacity = 1)
        val events: SharedFlow<AddPersonEvent> = _events.asSharedFlow()

        fun onNameChange(name: String) {
            _state.update { it.copy(name = name, nameError = false) }
        }

        fun onPhoneChange(phone: String) {
            _state.update { it.copy(phone = phone) }
        }

        fun onContactPicked(
            name: String?,
            phone: String?,
        ) {
            if (name.isNullOrBlank() && phone.isNullOrBlank()) {
                _events.tryEmit(AddPersonEvent.ContactPickFailed)
                return
            }
            _state.update {
                it.copy(
                    name = if (it.name.isBlank() && !name.isNullOrBlank()) name else it.name,
                    phone = phone ?: it.phone,
                )
            }
        }

        /** Debounced (~300 ms in the field) type-ahead query → fuzzy-ranked existing parties. */
        fun onQueryDebounced(query: String) {
            viewModelScope.launch {
                val parties = allParties()
                val ranked = FuzzyNameMatcher.rank(query, parties.map { it.name })
                _state.update { current ->
                    current.copy(suggestions = ranked.mapNotNull { name -> parties.find { it.name == name } })
                }
            }
        }

        fun onSuggestionSelected(name: String) {
            viewModelScope.launch {
                allParties().find { it.name == name }?.let {
                    _events.emit(AddPersonEvent.SteeredToExisting(it.id, it.name))
                }
            }
        }

        fun save() {
            val name = _state.value.name.trim()
            if (name.isEmpty()) {
                _state.update { it.copy(nameError = true) }
                return
            }
            viewModelScope.launch {
                _state.update { it.copy(saving = true) }
                // Exact (normalized) duplicate → steer to the existing party; the unique
                // (business_id, name) index makes a second insert destructive, never do it.
                val existing = allParties().find { FuzzyNameMatcher.normalize(it.name) == FuzzyNameMatcher.normalize(name) }
                if (existing != null) {
                    _events.emit(AddPersonEvent.SteeredToExisting(existing.id, existing.name))
                    return@launch
                }
                val now = clock.instant()
                val party =
                    Party(
                        id = UUID.randomUUID().toString(),
                        businessId = businessId,
                        name = name,
                        phone =
                            _state.value.phone
                                .trim()
                                .ifEmpty { null },
                        createdAt = now,
                        updatedAt = now,
                    )
                expensesRepository.saveParty(party)
                _events.emit(AddPersonEvent.Created(party.id))
            }
        }

        private suspend fun allParties(): List<Party> = expensesRepository.partiesWithBalance(businessId).first().map { it.party }
    }
