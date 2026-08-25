package com.itsluminous.samaroh.core.data.repository

import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.dao.ExpenseAttachmentDao
import com.itsluminous.samaroh.core.database.dao.ExpenseDao
import com.itsluminous.samaroh.core.database.dao.PartyDao
import com.itsluminous.samaroh.core.database.entity.ExpenseAttachmentEntity
import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.ExpenseAttachment
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.model.Party
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Additive ledger-support contract for feature:expenses (W1-B; docs/decisions.md ADR-007).
 * Complements the frozen ExpensesRepository without modifying it: header totals,
 * last-entry times, single-row lookups, and attachment metadata handling.
 */

/** Live "You gave"/"You got" header totals in paise (§4.2 home card). */
data class ExpenseTotals(
    /** Σ amount of live 'paid' entries — rendered red. */
    val gavePaise: Long,
    /** Σ amount of live 'received' entries — rendered green. */
    val gotPaise: Long,
)

/**
 * An attachment metadata row plus its Room-only local file path. The file itself lives in
 * Google Drive once uploaded; [isPendingUpload] drives the visible pending badge (§4.2).
 */
data class AttachmentWithLocalState(
    val attachment: ExpenseAttachment,
    /** On-device copy while upload pends (and as a thumbnail cache); never synced. */
    val localCachePath: String?,
) {
    val isPendingUpload: Boolean get() = attachment.driveFileId == null
}

interface ExpensesLedgerRepository {
    fun totals(businessId: String): Flow<ExpenseTotals>

    /** partyId → most recent live entry time; parties without entries are absent. */
    fun lastEntryPerParty(businessId: String): Flow<Map<String, Instant>>

    suspend fun party(id: String): Party?

    suspend fun expense(id: String): Expense?

    fun attachmentsForExpense(expenseId: String): Flow<List<AttachmentWithLocalState>>

    /** All live attachments across a party's entries, for ledger-row thumbnails. */
    fun attachmentsForParty(partyId: String): Flow<List<AttachmentWithLocalState>>

    /**
     * Persists the metadata row (only metadata syncs — the file goes to Google Drive via
     * [com.itsluminous.samaroh.core.data.attachments.AttachmentUploadQueue]).
     */
    suspend fun saveAttachment(
        attachment: ExpenseAttachment,
        localCachePath: String?,
    )

    suspend fun deleteAttachment(id: String)
}

@Singleton
class RoomExpensesLedgerRepository
    @Inject
    constructor(
        private val expenseDao: ExpenseDao,
        private val partyDao: PartyDao,
        private val attachmentDao: ExpenseAttachmentDao,
        private val outboxWriter: OutboxWriter,
        private val clock: Clock,
    ) : ExpensesLedgerRepository {
        private val json = Json { encodeDefaults = true }

        override fun totals(businessId: String): Flow<ExpenseTotals> =
            combine(
                expenseDao.totalPaiseFlow(businessId, ExpenseDirection.PAID.wire),
                expenseDao.totalPaiseFlow(businessId, ExpenseDirection.RECEIVED.wire),
            ) { gave, got -> ExpenseTotals(gavePaise = gave, gotPaise = got) }

        override fun lastEntryPerParty(businessId: String): Flow<Map<String, Instant>> =
            expenseDao.lastEntryPerParty(businessId).map { rows ->
                rows.mapNotNull { row -> row.lastEntryAt?.let { row.partyId to it } }.toMap()
            }

        override suspend fun party(id: String): Party? = partyDao.byId(id)?.toModel()

        override suspend fun expense(id: String): Expense? = expenseDao.byId(id)?.toModel()

        override fun attachmentsForExpense(expenseId: String): Flow<List<AttachmentWithLocalState>> =
            attachmentDao.attachmentsForExpense(expenseId).map { list -> list.map { it.toModelWithLocalState() } }

        override fun attachmentsForParty(partyId: String): Flow<List<AttachmentWithLocalState>> =
            attachmentDao.attachmentsForParty(partyId).map { list -> list.map { it.toModelWithLocalState() } }

        override suspend fun saveAttachment(
            attachment: ExpenseAttachment,
            localCachePath: String?,
        ) {
            attachmentDao.upsert(attachment.toEntity(localCachePath))
            outboxWriter.enqueue(
                "expense_attachments",
                attachment.id,
                OutboxOperation.UPSERT,
                json.encodeToString(ExpenseAttachment.serializer(), attachment),
            )
        }

        override suspend fun deleteAttachment(id: String) {
            val now = clock.instant()
            attachmentDao.tombstone(id, now)
            outboxWriter.enqueue(
                "expense_attachments",
                id,
                OutboxOperation.DELETE,
                json.encodeToString(
                    kotlinx.serialization.json.JsonObject
                        .serializer(),
                    buildJsonObject {
                        put("id", id)
                        put("deleted_at", now.toString())
                    },
                ),
            )
        }
    }

private fun ExpenseAttachmentEntity.toModelWithLocalState() =
    AttachmentWithLocalState(
        attachment =
            ExpenseAttachment(
                id = id,
                expenseId = expenseId,
                businessId = businessId,
                driveFileId = driveFileId,
                mimeType = mimeType,
                fileName = fileName,
                createdAt = createdAt,
                deletedAt = deletedAt,
            ),
        localCachePath = localCachePath,
    )

private fun ExpenseAttachment.toEntity(localCachePath: String?) =
    ExpenseAttachmentEntity(
        id = id,
        expenseId = expenseId,
        businessId = businessId,
        driveFileId = driveFileId,
        mimeType = mimeType,
        fileName = fileName,
        localCachePath = localCachePath,
        createdAt = createdAt,
        deletedAt = deletedAt,
    )
