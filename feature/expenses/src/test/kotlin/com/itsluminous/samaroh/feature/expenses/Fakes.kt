package com.itsluminous.samaroh.feature.expenses

import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.data.repository.ExpenseTotals
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.repository.PartyWithNetBalance
import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.ExpenseAttachment
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.model.Party
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant

/** In-memory [ExpensesRepository] driving ViewModel flow tests. */
class FakeExpensesRepository : ExpensesRepository {
    val parties = MutableStateFlow<List<Party>>(emptyList())
    val expenses = MutableStateFlow<List<Expense>>(emptyList())
    val deletedExpenseIds = mutableListOf<String>()
    val savedParties = mutableListOf<Party>()

    override fun partiesWithBalance(businessId: String): Flow<List<PartyWithNetBalance>> =
        combine(parties, expenses) { partyList, expenseList ->
            partyList.map { party ->
                val net =
                    expenseList
                        .filter { it.partyId == party.id }
                        .sumOf { if (it.direction == ExpenseDirection.PAID) it.amountPaise else -it.amountPaise }
                PartyWithNetBalance(party, net)
            }
        }

    override suspend fun searchParties(
        businessId: String,
        query: String,
    ): List<Party> = parties.value.filter { it.name.contains(query, ignoreCase = true) }

    override suspend fun saveParty(party: Party) {
        savedParties += party
        parties.value = parties.value.filter { it.id != party.id } + party
    }

    override suspend fun deleteParty(id: String) {
        parties.value = parties.value.filter { it.id != id }
    }

    override fun entriesForParty(partyId: String): Flow<List<Expense>> =
        expenses.map { list ->
            list
                .filter { it.partyId == partyId }
                .sortedWith(compareByDescending<Expense> { it.expenseDate }.thenByDescending { it.createdAt })
        }

    override suspend fun saveExpense(expense: Expense) {
        expenses.value = expenses.value.filter { it.id != expense.id } + expense
    }

    override suspend fun deleteExpense(id: String) {
        deletedExpenseIds += id
        expenses.value = expenses.value.filter { it.id != id }
    }
}

/** In-memory [ExpensesLedgerRepository]. */
class FakeExpensesLedgerRepository : ExpensesLedgerRepository {
    val parties = MutableStateFlow<List<Party>>(emptyList())
    val expenses = MutableStateFlow<List<Expense>>(emptyList())
    val attachments = MutableStateFlow<List<AttachmentWithLocalState>>(emptyList())
    val savedAttachments = mutableListOf<Pair<ExpenseAttachment, String?>>()

    override fun totals(businessId: String): Flow<ExpenseTotals> =
        expenses.map { list ->
            ExpenseTotals(
                gavePaise = list.filter { it.direction == ExpenseDirection.PAID }.sumOf { it.amountPaise },
                gotPaise = list.filter { it.direction == ExpenseDirection.RECEIVED }.sumOf { it.amountPaise },
            )
        }

    override fun lastEntryPerParty(businessId: String): Flow<Map<String, Instant>> =
        expenses.map { list ->
            list.groupBy { it.partyId }.mapValues { (_, entries) -> entries.maxOf { it.createdAt } }
        }

    override suspend fun party(id: String): Party? = parties.value.find { it.id == id }

    override suspend fun expense(id: String): Expense? = expenses.value.find { it.id == id }

    override fun attachmentsForExpense(expenseId: String): Flow<List<AttachmentWithLocalState>> =
        attachments.map { list -> list.filter { it.attachment.expenseId == expenseId } }

    override fun attachmentsForParty(partyId: String): Flow<List<AttachmentWithLocalState>> =
        combine(attachments, expenses) { attachmentList, expenseList ->
            val liveIds = expenseList.filter { it.partyId == partyId }.map { it.id }.toSet()
            attachmentList.filter { it.attachment.expenseId in liveIds }
        }

    override suspend fun saveAttachment(
        attachment: ExpenseAttachment,
        localCachePath: String?,
    ) {
        savedAttachments += attachment to localCachePath
        attachments.value = attachments.value + AttachmentWithLocalState(attachment, localCachePath)
    }

    override suspend fun deleteAttachment(id: String) {
        attachments.value = attachments.value.filter { it.attachment.id != id }
    }
}

/** Session fake: fixed business + optional signed-in user, full-access permissions. */
fun fakeExpensesSession(
    business: com.itsluminous.samaroh.core.model.Business? =
        com.itsluminous.samaroh.core.testing.Fixtures
            .business(),
    userId: String? = null,
): ExpensesSession =
    ExpensesSession(
        activeBusinessProvider =
            object : com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider {
                override val activeBusiness = kotlinx.coroutines.flow.MutableStateFlow(business)
            },
        currentUserProvider =
            object : com.itsluminous.samaroh.core.data.session.CurrentUserProvider {
                override val currentUserId = kotlinx.coroutines.flow.MutableStateFlow(userId)
            },
        permissionGuard =
            object : com.itsluminous.samaroh.core.auth.PermissionGuard {
                override fun permissions(businessId: String) =
                    kotlinx.coroutines.flow.MutableStateFlow(
                        com.itsluminous.samaroh.core.model
                            .MemberPermissions(),
                    )

                override fun isOwner(businessId: String) = kotlinx.coroutines.flow.MutableStateFlow(true)
            },
    )
