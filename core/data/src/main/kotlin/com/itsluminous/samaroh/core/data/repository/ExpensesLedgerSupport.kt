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

    /**
     * ADDITIVE view-attachment support (ADR-052): records where a Drive-downloaded
     * attachment file was cached on THIS device. `local_cache_path` is Room-only state
     * (never synced), so — unlike every other write here — no outbox op is enqueued.
     */
    suspend fun updateAttachmentLocalCachePath(
        id: String,
        localCachePath: String,
    )

    /**
     * Party delete (ADR-028; return widened by ADR-063): tombstones the party AND
     * cascades to its live expenses and their attachments — children first
     * (attachments → expenses → party), one outbox DELETE row per tombstone so the
     * server mirrors the cascade. Returns every tombstoned attachment's device state
     * so the caller can remove local cache files AND best-effort delete the Drive
     * copies (the ADR-053 viewer-delete pattern, extended to cascades).
     */
    suspend fun deletePartyCascade(partyId: String): List<CascadeDeletedAttachment>

    /**
     * Entry delete cascade (ADR-063, additive): tombstones the entry's live attachments
     * (one outbox DELETE each) and then the expense row itself — children first, the
     * party-cascade shape scoped to one entry. Returns the tombstoned attachments'
     * device state for local-file and best-effort Drive cleanup, like
     * [deletePartyCascade]. The plain `ExpensesRepository.deleteExpense` (no attachment
     * handling) remains for rows known to carry none.
     */
    suspend fun deleteExpenseCascade(expenseId: String): List<CascadeDeletedAttachment>
}

/** Device-relevant remains of an attachment tombstoned by a cascade delete (ADR-063). */
data class CascadeDeletedAttachment(
    val attachmentId: String,
    val driveFileId: String?,
    val localCachePath: String?,
)

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
            outboxWriter.enqueue("expense_attachments", id, OutboxOperation.DELETE, deletePayload(id, now))
        }

        override suspend fun updateAttachmentLocalCachePath(
            id: String,
            localCachePath: String,
        ) {
            // Device-only state (the file cache location); deliberately NO outbox enqueue.
            attachmentDao.updateLocalCachePath(id, localCachePath)
        }

        override suspend fun deletePartyCascade(partyId: String): List<CascadeDeletedAttachment> {
            val now = clock.instant()
            // Children first — mirrors the server FK order (attachments → expenses → party).
            val attachments = attachmentDao.liveForParty(partyId)
            attachments.forEach { attachment ->
                attachmentDao.tombstone(attachment.id, now)
                outboxWriter.enqueue("expense_attachments", attachment.id, OutboxOperation.DELETE, deletePayload(attachment.id, now))
            }
            expenseDao.liveForParty(partyId).forEach { expense ->
                expenseDao.tombstone(expense.id, now)
                outboxWriter.enqueue("expenses", expense.id, OutboxOperation.DELETE, deletePayload(expense.id, now))
            }
            partyDao.tombstone(partyId, now)
            outboxWriter.enqueue("parties", partyId, OutboxOperation.DELETE, deletePayload(partyId, now))
            return attachments.map { CascadeDeletedAttachment(it.id, it.driveFileId, it.localCachePath) }
        }

        override suspend fun deleteExpenseCascade(expenseId: String): List<CascadeDeletedAttachment> {
            val now = clock.instant()
            // Children first — attachments, then the entry (ADR-063 entry cascade).
            val attachments = attachmentDao.liveForExpense(expenseId)
            attachments.forEach { attachment ->
                attachmentDao.tombstone(attachment.id, now)
                outboxWriter.enqueue("expense_attachments", attachment.id, OutboxOperation.DELETE, deletePayload(attachment.id, now))
            }
            expenseDao.tombstone(expenseId, now)
            outboxWriter.enqueue("expenses", expenseId, OutboxOperation.DELETE, deletePayload(expenseId, now))
            return attachments.map { CascadeDeletedAttachment(it.id, it.driveFileId, it.localCachePath) }
        }

        private fun deletePayload(
            id: String,
            at: Instant,
        ): String =
            json.encodeToString(
                kotlinx.serialization.json.JsonObject
                    .serializer(),
                buildJsonObject {
                    put("id", id)
                    put("deleted_at", at.toString())
                },
            )
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
