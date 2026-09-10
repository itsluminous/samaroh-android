package com.itsluminous.samaroh.feature.expenses.sharetarget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.feature.expenses.ExpensesSession
import com.itsluminous.samaroh.feature.expenses.domain.FuzzyNameMatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Gate verdict of the Create-invoice share flow (ADR-078). */
enum class ShareTargetGate {
    /** Session/permission checks still resolving — render nothing yet. */
    LOADING,

    /** Nobody is signed in (or no business yet) — graceful message, no picker. */
    NOT_SIGNED_IN,

    /** Signed in but the member lacks `expenses.create` — graceful message. */
    NO_PERMISSION,

    /** Party picker renders. */
    READY,
}

data class SharePartyPickerState(
    val gate: ShareTargetGate = ShareTargetGate.LOADING,
    val query: String = "",
    val parties: List<Party> = emptyList(),
    val hasAnyParty: Boolean = false,
)

/**
 * Party picker of the Create-invoice share target (ADR-078): requires a signed-in
 * session AND `expenses.create` (owners pass); the list is the business's parties
 * with the same normalized type-ahead matching the add-person flow uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SharePartyPickerViewModel
    @Inject
    constructor(
        expensesRepository: ExpensesRepository,
        session: ExpensesSession,
        currentUserProvider: CurrentUserProvider,
        private val holder: ShareTargetHolder,
    ) : ViewModel() {
        private val query = MutableStateFlow("")

        val state: StateFlow<SharePartyPickerState> =
            combine(
                currentUserProvider.currentUserId,
                session.businessIdFlow,
                session.canCreateEntries,
            ) { userId, businessId, canCreate ->
                Triple(userId, businessId, canCreate)
            }.flatMapLatest { (userId, businessId, canCreate) ->
                when {
                    // The share flow REQUIRES a real session (unlike the in-app
                    // owner-mode default): a signed-out device gets the message.
                    userId == null -> kotlinx.coroutines.flow.flowOf(SharePartyPickerState(gate = ShareTargetGate.NOT_SIGNED_IN))
                    !canCreate -> kotlinx.coroutines.flow.flowOf(SharePartyPickerState(gate = ShareTargetGate.NO_PERMISSION))
                    else ->
                        combine(expensesRepository.partiesWithBalance(businessId), query) { parties, q ->
                            val all = parties.map { it.party }
                            SharePartyPickerState(
                                gate = ShareTargetGate.READY,
                                query = q,
                                parties = all.filterBy(q),
                                hasAnyParty = all.isNotEmpty(),
                            )
                        }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharePartyPickerState())

        fun onQueryChange(value: String) {
            query.value = value
        }

        /** Abandoning the flow (back / message OK) drops the pending shared file. */
        fun abandon() {
            holder.clear()
        }

        private fun List<Party>.filterBy(query: String): List<Party> {
            val normalized = FuzzyNameMatcher.normalize(query)
            if (normalized.isEmpty()) return this
            return filter { FuzzyNameMatcher.normalize(it.name).contains(normalized) }
        }
    }
