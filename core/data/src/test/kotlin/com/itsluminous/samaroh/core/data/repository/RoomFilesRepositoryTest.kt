package com.itsluminous.samaroh.core.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.model.FileItem
import com.itsluminous.samaroh.core.model.Folder
import com.itsluminous.samaroh.core.model.FolderAccess
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * FILES repository (ADR-085): Room + outbox in one step, staged rows without a Drive id,
 * whole-row tombstone upserts, children-first folder cascade, composite access ids.
 */
@RunWith(RobolectricTestRunner::class)
class RoomFilesRepositoryTest {
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

    private val now: Instant = Instant.parse("2026-09-25T06:00:00Z")
    private lateinit var db: SamarohDatabase
    private lateinit var outbox: RecordingOutboxWriter
    private lateinit var repository: RoomFilesRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        outbox = RecordingOutboxWriter()
        repository = RoomFilesRepository(db.folderDao(), db.fileDao(), db.folderAccessDao(), outbox, Clock.fixed(now, ZoneOffset.UTC))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun folder(
        id: String,
        parentId: String? = null,
        name: String = "F-$id",
    ) = Folder(id, "biz", parentId, name, false, "u-1", null, now, now, null)

    private fun file(
        id: String,
        folderId: String?,
    ) = FileItem(id, "biz", folderId, "$id.jpg", "image/jpeg", 10, null, "u-1", now, now, null)

    @Test
    fun `stageFile keeps the drive id null locally and in the outbox payload`() =
        runTest {
            repository.stageFile(file("file-1", null), localPath = "/staging/file-1")

            val stored = repository.file("file-1")!!
            assertThat(stored.file.driveFileId).isNull()
            assertThat(stored.localCachePath).isEqualTo("/staging/file-1")
            val record = outbox.records.single()
            assertThat(record.entityType).isEqualTo("files")
            assertThat(record.operation).isEqualTo(OutboxOperation.UPSERT)
            assertThat(Json.parseToJsonElement(record.payloadJson).jsonObject["drive_file_id"]).isEqualTo(JsonNull)
            assertThat(Json.parseToJsonElement(record.payloadJson).jsonObject.keys).doesNotContain("local_cache_path")
        }

    @Test
    fun `deleteFile tombstones as an upsert with deleted_at and bumps updated_at`() =
        runTest {
            repository.stageFile(file("file-1", null).copy(driveFileId = "d-1"), "/c/file-1")
            outbox.records.clear()

            val removed = repository.deleteFile("file-1")!!

            assertThat(removed.file.deletedAt).isEqualTo(now)
            assertThat(removed.file.updatedAt).isEqualTo(now)
            val record = outbox.records.single()
            assertThat(record.operation).isEqualTo(OutboxOperation.UPSERT)
            val payload = Json.parseToJsonElement(record.payloadJson).jsonObject
            assertThat(payload.getValue("deleted_at").jsonPrimitive.content).isNotEmpty()
            assertThat(payload.getValue("drive_file_id").jsonPrimitive.content).isEqualTo("d-1")
            assertThat(repository.files("biz").first()).isEmpty()
        }

    @Test
    fun `deleteFolderTree tombstones the subtree children first, files before folders`() =
        runTest {
            repository.saveFolder(folder("root"))
            repository.saveFolder(folder("child", parentId = "root"))
            repository.saveFolder(folder("grandchild", parentId = "child"))
            repository.saveFolder(folder("sibling"))
            repository.stageFile(file("in-root", "root").copy(driveFileId = "d-root"), "/c/in-root")
            repository.stageFile(file("in-grandchild", "grandchild"), "/c/in-gc")
            repository.stageFile(file("elsewhere", "sibling"), "/c/elsewhere")
            outbox.records.clear()

            val removedFiles = repository.deleteFolderTree("root")

            assertThat(removedFiles.map { it.file.id }).containsExactly("in-root", "in-grandchild")
            val order = outbox.records.map { it.entityType to it.entityId }
            assertThat(order.filter { it.first == "files" }.map { it.second }).containsExactly("in-root", "in-grandchild")
            assertThat(order.filter { it.first == "folders" }.map { it.second }).containsExactly("grandchild", "child", "root").inOrder()
            // Files precede folders; every op is an UPSERT carrying a tombstone.
            assertThat(order.indexOfLast { it.first == "files" }).isLessThan(order.indexOfFirst { it.first == "folders" })
            assertThat(outbox.records.all { it.operation == OutboxOperation.UPSERT }).isTrue()
            assertThat(repository.folders("biz").first().map { it.id }).containsExactly("sibling")
            assertThat(repository.files("biz").first().map { it.file.id }).containsExactly("elsewhere")
            assertThat(repository.folder("grandchild")!!.deletedAt).isEqualTo(now)
        }

    @Test
    fun `folder access uses the composite entity id and soft revoke`() =
        runTest {
            val grant = FolderAccess("f-1", "m-1", "biz", now, now, null)
            repository.saveFolderAccess(grant)
            repository.saveFolderAccess(grant.copy(deletedAt = now))

            assertThat(outbox.records.map { it.entityId }).containsExactly("f-1|m-1", "f-1|m-1")
            assertThat(outbox.records.all { it.operation == OutboxOperation.UPSERT }).isTrue()
            assertThat(repository.folderAccess("biz").first()).isEmpty()
            assertThat(repository.folderAccessRows("f-1").single().deletedAt).isEqualTo(now)
        }

    @Test
    fun `liveSiblingNamed trims and matches case-insensitively`() =
        runTest {
            repository.saveFolder(folder("f-1", name = "Contracts"))
            assertThat(repository.liveSiblingNamed("biz", null, "  contracts ")!!.id).isEqualTo("f-1")
            assertThat(repository.liveSiblingNamed("biz", "f-1", "contracts")).isNull()
        }
}
