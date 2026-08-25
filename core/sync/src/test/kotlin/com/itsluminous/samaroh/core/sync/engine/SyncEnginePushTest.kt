package com.itsluminous.samaroh.core.sync.engine

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.AttachmentUploader
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.ExpenseAttachmentEntity
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.model.ExpenseAttachment
import com.itsluminous.samaroh.core.sync.remote.RemoteRejectedException
import com.itsluminous.samaroh.core.sync.remote.RemoteUnavailableException
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEnginePushTest {
    private lateinit var db: SamarohDatabase
    private lateinit var remote: FakeRemoteStore

    @Before
    fun setUp() {
        db = newTestDatabase()
        remote = FakeRemoteStore()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `outbox pushes FIFO and clears the queue`() =
        runTest {
            val first = Fixtures.booking(id = "b-1")
            val second = Fixtures.booking(id = "b-2")
            db.outboxDao().enqueue(bookingOutboxEntry(first))
            db.outboxDao().enqueue(bookingOutboxEntry(second))

            val outcome = syncEngine(db, remote).runSync()

            assertThat(outcome.pushedCount).isEqualTo(2)
            assertThat(
                remote.upserts.map {
                    it.second
                        .getValue("id")
                        .jsonPrimitive.content
                },
            ).containsExactly("b-1", "b-2")
                .inOrder()
            assertThat(db.outboxDao().nextBatch()).isEmpty()
        }

    @Test
    fun `pushed rows carry decimal rupees on the wire`() =
        runTest {
            db.outboxDao().enqueue(bookingOutboxEntry(Fixtures.booking(id = "b-1", totalAmountPaise = 10_651_161L)))

            syncEngine(db, remote).runSync()

            val row = remote.upserts.single().second
            assertThat(row.getValue("total_amount").jsonPrimitive.content).isEqualTo("106511.61")
        }

    @Test
    fun `delete ops propagate tombstones instead of upserting`() =
        runTest {
            db.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "bookings",
                    entityId = "b-9",
                    operation = "delete",
                    payloadJson = """{"id":"b-9","deleted_at":"2026-08-25T11:00:00Z"}""",
                    createdAt = FIXED_NOW,
                ),
            )

            syncEngine(db, remote).runSync()

            assertThat(remote.upserts).isEmpty()
            assertThat(remote.tombstones).containsExactly(Triple("bookings", "b-9", "2026-08-25T11:00:00Z"))
        }

    @Test
    fun `rejected item is marked error while other entities keep pushing`() =
        runTest {
            val rejected = Fixtures.booking(id = "b-reject")
            val followUp = Fixtures.booking(id = "b-reject")
            val unrelated = Fixtures.booking(id = "b-ok")
            db.outboxDao().enqueue(bookingOutboxEntry(rejected))
            db.outboxDao().enqueue(bookingOutboxEntry(followUp))
            db.outboxDao().enqueue(bookingOutboxEntry(unrelated))
            remote.onUpsert = { _, row ->
                if (row.getValue("id").jsonPrimitive.content == "b-reject") {
                    RemoteRejectedException("row-level security violation")
                } else {
                    null
                }
            }

            val outcome = syncEngine(db, remote).runSync()

            assertThat(outcome.pushedCount).isEqualTo(1)
            assertThat(outcome.itemErrorCount).isEqualTo(1)
            // Both ops for the failing entity stay queued (per-entity FIFO preserved).
            val remaining = db.outboxDao().nextBatch()
            assertThat(remaining.map { it.entityId }).containsExactly("b-reject", "b-reject").inOrder()
            val errored = db.outboxDao().erroredEntries().first()
            assertThat(errored).hasSize(1)
            assertThat(errored.single().lastError).contains("security")
            assertThat(errored.single().attemptCount).isEqualTo(1)
        }

    @Test
    fun `network failure aborts the run and requests retry`() =
        runTest {
            db.outboxDao().enqueue(bookingOutboxEntry(Fixtures.booking(id = "b-1")))
            remote.onUpsert = { _, _ -> RemoteUnavailableException("offline") }

            val outcome = syncEngine(db, remote).runSync()

            assertThat(outcome.networkFailed).isTrue()
            assertThat(db.outboxDao().nextBatch()).hasSize(1)
        }

    @Test
    fun `unconfigured credentials make the run a successful no-op`() =
        runTest {
            db.outboxDao().enqueue(bookingOutboxEntry(Fixtures.booking(id = "b-1")))

            val outcome = syncEngine(db, remote = null).runSync()

            assertThat(outcome.configured).isFalse()
            assertThat(outcome.networkFailed).isFalse()
            assertThat(db.outboxDao().nextBatch()).hasSize(1)
        }

    @Test
    fun `attachment uploads first and the pushed row carries the drive file id`() =
        runTest {
            val attachment = attachmentRow(driveFileId = null)
            db.expenseAttachmentDao().upsert(attachment)
            db.outboxDao().enqueue(attachmentOutboxEntry(attachment.id))
            val uploader = FakeAttachmentUploader(AttachmentUploader.UploadResult.Uploaded("drive-42"))

            val outcome = syncEngine(db, remote, uploader = uploader).runSync()

            assertThat(uploader.uploaded).containsExactly(attachment.id)
            assertThat(outcome.pushedCount).isEqualTo(1)
            val row = remote.upserts.single()
            assertThat(row.first).isEqualTo("expense_attachments")
            assertThat(
                row.second
                    .getValue("drive_file_id")
                    .jsonPrimitive.content,
            ).isEqualTo("drive-42")
            // The Room row is patched too, preserving the local cache path.
            val updated = db.expenseAttachmentDao().byId(attachment.id)
            assertThat(updated?.driveFileId).isEqualTo("drive-42")
            assertThat(updated?.localCachePath).isEqualTo("/cache/a.jpg")
        }

    @Test
    fun `attachment stays queued with pending error while no uploader is bound`() =
        runTest {
            val attachment = attachmentRow(driveFileId = null)
            db.expenseAttachmentDao().upsert(attachment)
            db.outboxDao().enqueue(attachmentOutboxEntry(attachment.id))

            val outcome = syncEngine(db, remote, uploader = null).runSync()

            assertThat(outcome.pushedCount).isEqualTo(0)
            assertThat(remote.upserts).isEmpty()
            val errored = db.outboxDao().erroredEntries().first()
            assertThat(errored.single().lastError).isEqualTo(SyncEngine.ERROR_STORAGE_NOT_LINKED)
            assertThat(db.outboxDao().nextBatch()).hasSize(1)
        }

    @Test
    fun `attachment already uploaded pushes without calling the uploader`() =
        runTest {
            val attachment = attachmentRow(driveFileId = "drive-7")
            db.expenseAttachmentDao().upsert(attachment)
            db.outboxDao().enqueue(attachmentOutboxEntry(attachment.id, driveFileId = "drive-7"))
            val uploader = FakeAttachmentUploader(AttachmentUploader.UploadResult.NotLinked)

            val outcome = syncEngine(db, remote, uploader = uploader).runSync()

            assertThat(uploader.uploaded).isEmpty()
            assertThat(outcome.pushedCount).isEqualTo(1)
        }

    private fun attachmentRow(driveFileId: String?): ExpenseAttachmentEntity =
        ExpenseAttachmentEntity(
            id = "att-1",
            expenseId = "e-1",
            businessId = Fixtures.BUSINESS_ID,
            driveFileId = driveFileId,
            mimeType = "image/jpeg",
            fileName = "a.jpg",
            localCachePath = "/cache/a.jpg",
            createdAt = FIXED_NOW,
        )

    private fun attachmentOutboxEntry(
        attachmentId: String,
        driveFileId: String? = null,
    ): OutboxEntity {
        val model =
            ExpenseAttachment(
                id = attachmentId,
                expenseId = "e-1",
                businessId = Fixtures.BUSINESS_ID,
                driveFileId = driveFileId,
                mimeType = "image/jpeg",
                fileName = "a.jpg",
                createdAt = FIXED_NOW,
            )
        return OutboxEntity(
            entityType = "expense_attachments",
            entityId = attachmentId,
            operation = "upsert",
            payloadJson = testJson.encodeToString(ExpenseAttachment.serializer(), model),
            createdAt = FIXED_NOW,
        )
    }
}
