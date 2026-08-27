package com.itsluminous.samaroh.core.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.model.ExpenseAttachment
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.ZoneOffset

/** Party-delete cascade (ADR-028): party → expenses → attachments tombstoned + outbox rows. */
@RunWith(RobolectricTestRunner::class)
class RoomExpensesLedgerRepositoryTest {
    /** One recorded outbox enqueue. */
    private data class OutboxRecord(
        val entityType: String,
        val entityId: String,
        val operation: OutboxOperation,
        val payloadJson: String,
    )

    private class RecordingOutboxWriter : OutboxWriter {
        val records = mutableListOf<OutboxRecord>()

        override suspend fun enqueue(
            entityType: String,
            entityId: String,
            operation: OutboxOperation,
            payloadJson: String,
        ) {
            records += OutboxRecord(entityType, entityId, operation, payloadJson)
        }
    }

    private val deleteInstant = Fixtures.NOW.plusSeconds(60)
    private lateinit var db: SamarohDatabase
    private lateinit var outbox: RecordingOutboxWriter
    private lateinit var repository: RoomExpensesLedgerRepository

    private val party = Fixtures.party(name = "cascade-party")
    private val otherParty = Fixtures.party(name = "survivor-party")
    private val expense1 = Fixtures.expense(partyId = party.id)
    private val expense2 = Fixtures.expense(partyId = party.id)
    private val otherExpense = Fixtures.expense(partyId = otherParty.id)

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        outbox = RecordingOutboxWriter()
        repository =
            RoomExpensesLedgerRepository(
                expenseDao = db.expenseDao(),
                partyDao = db.partyDao(),
                attachmentDao = db.expenseAttachmentDao(),
                outboxWriter = outbox,
                clock = Clock.fixed(deleteInstant, ZoneOffset.UTC),
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun attachment(
        expenseId: String,
        driveFileId: String? = null,
    ): ExpenseAttachment =
        ExpenseAttachment(
            id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
            expenseId = expenseId,
            businessId = party.businessId,
            driveFileId = driveFileId,
            mimeType = "image/jpeg",
            fileName = "bill.jpg",
            createdAt = Fixtures.NOW,
        )

    private suspend fun seed(): Pair<ExpenseAttachment, ExpenseAttachment> {
        db.partyDao().upsert(party.toEntity())
        db.partyDao().upsert(otherParty.toEntity())
        db.expenseDao().upsert(expense1.toEntity())
        db.expenseDao().upsert(expense2.toEntity())
        db.expenseDao().upsert(otherExpense.toEntity())
        // Pending-upload attachment (local file only) + Drive-backed attachment (metadata only).
        val pending = attachment(expense1.id)
        val driveBacked = attachment(expense2.id, driveFileId = "drive-file-1")
        repository.saveAttachment(pending, localCachePath = "/tmp/pending-bill.jpg")
        repository.saveAttachment(driveBacked, localCachePath = null)
        repository.saveAttachment(attachment(otherExpense.id), localCachePath = "/tmp/survivor-bill.jpg")
        outbox.records.clear() // Only the cascade's own rows matter below.
        return pending to driveBacked
    }

    @Test
    fun `cascade tombstones the party, its expenses and their attachments`() =
        runTest {
            val (pending, driveBacked) = seed()

            repository.deletePartyCascade(party.id)

            assertThat(db.partyDao().byId(party.id)?.deletedAt).isEqualTo(deleteInstant)
            assertThat(db.expenseDao().byId(expense1.id)?.deletedAt).isEqualTo(deleteInstant)
            assertThat(db.expenseDao().byId(expense2.id)?.deletedAt).isEqualTo(deleteInstant)
            assertThat(db.expenseAttachmentDao().byId(pending.id)?.deletedAt).isEqualTo(deleteInstant)
            assertThat(db.expenseAttachmentDao().byId(driveBacked.id)?.deletedAt).isEqualTo(deleteInstant)
        }

    @Test
    fun `cascade enqueues one outbox DELETE per tombstoned row, children first`() =
        runTest {
            val (pending, driveBacked) = seed()

            repository.deletePartyCascade(party.id)

            assertThat(outbox.records).hasSize(5)
            assertThat(outbox.records.map { it.operation }.distinct()).containsExactly(OutboxOperation.DELETE)
            // Children before parents so the server can mirror the cascade in order.
            assertThat(outbox.records.map { it.entityType }).isEqualTo(
                listOf("expense_attachments", "expense_attachments", "expenses", "expenses", "parties"),
            )
            assertThat(outbox.records.map { it.entityId }).containsExactly(
                pending.id,
                driveBacked.id,
                expense1.id,
                expense2.id,
                party.id,
            )
            outbox.records.forEach { record ->
                assertThat(record.payloadJson).contains("\"deleted_at\":\"$deleteInstant\"")
            }
        }

    @Test
    fun `cascade returns only the local cache paths of tombstoned attachments`() =
        runTest {
            seed()

            val localCachePaths = repository.deletePartyCascade(party.id)

            // The Drive-backed attachment has no local file; the survivor party's file stays.
            assertThat(localCachePaths).containsExactly("/tmp/pending-bill.jpg")
        }

    @Test
    fun `cascade leaves other parties and their data live`() =
        runTest {
            seed()

            repository.deletePartyCascade(party.id)

            assertThat(db.partyDao().byId(otherParty.id)?.deletedAt).isNull()
            assertThat(db.expenseDao().byId(otherExpense.id)?.deletedAt).isNull()
            assertThat(repository.attachmentsForParty(otherParty.id).first()).hasSize(1)
        }

    @Test
    fun `deleting a party with no entries only tombstones the party`() =
        runTest {
            db.partyDao().upsert(party.toEntity())

            val localCachePaths = repository.deletePartyCascade(party.id)

            assertThat(localCachePaths).isEmpty()
            assertThat(outbox.records).hasSize(1)
            assertThat(outbox.records.single().entityType).isEqualTo("parties")
            assertThat(db.partyDao().byId(party.id)?.deletedAt).isEqualTo(deleteInstant)
        }
}
