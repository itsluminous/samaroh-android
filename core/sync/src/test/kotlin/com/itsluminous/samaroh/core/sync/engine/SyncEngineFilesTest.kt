package com.itsluminous.samaroh.core.sync.engine

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.FilesUploader
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.BusinessEntity
import com.itsluminous.samaroh.core.database.entity.FileEntity
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.database.entity.SyncCursorEntity
import com.itsluminous.samaroh.core.model.FileItem
import com.itsluminous.samaroh.core.sync.wire.SyncTables
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * FILES module sync wiring (ADR-085): spec entries, pulled rows apply (device-only
 * columns preserved), tombstones propagate through the single `updated_at` leg, the
 * composite `folder_access` keyset, and upload-before-row-push for `files`.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineFilesTest {
    private lateinit var db: SamarohDatabase
    private lateinit var remote: FakeRemoteStore
    private lateinit var notifier: RecordingConflictNotifier

    @Before
    fun setUp() {
        db = newTestDatabase()
        remote = FakeRemoteStore()
        notifier = RecordingConflictNotifier()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBusiness() {
        db.businessDao().upsert(
            BusinessEntity(
                id = Fixtures.BUSINESS_ID,
                name = "fixture-business",
                ownerName = "fixture-owner",
                ownerUserId = Fixtures.USER_ID,
                createdAt = Fixtures.NOW,
                updatedAt = Fixtures.NOW,
            ),
        )
    }

    private fun remoteFolderRow(
        id: String,
        updatedAt: String,
        deletedAt: String? = null,
        restricted: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("business_id", Fixtures.BUSINESS_ID)
            put("parent_id", JsonNull)
            put("name", "Contracts")
            put("restricted", restricted)
            put("created_by", Fixtures.USER_ID)
            put("updated_by", JsonNull)
            put("created_at", "2026-09-25T09:00:00+00:00")
            put("updated_at", updatedAt)
            if (deletedAt != null) put("deleted_at", deletedAt) else put("deleted_at", JsonNull)
        }

    private fun remoteFileRow(
        id: String,
        updatedAt: String,
        deletedAt: String? = null,
        driveFileId: String = "drive-$id",
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("business_id", Fixtures.BUSINESS_ID)
            put("folder_id", JsonNull)
            put("name", "lease.pdf")
            put("mime_type", "application/pdf")
            put("size_bytes", 1234)
            put("drive_file_id", driveFileId)
            put("created_by", Fixtures.USER_ID)
            put("created_at", "2026-09-25T09:00:00+00:00")
            put("updated_at", updatedAt)
            if (deletedAt != null) put("deleted_at", deletedAt) else put("deleted_at", JsonNull)
        }

    private fun remoteAccessRow(
        folderId: String,
        memberId: String,
        updatedAt: String,
        deletedAt: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("folder_id", folderId)
            put("member_id", memberId)
            put("business_id", Fixtures.BUSINESS_ID)
            put("created_at", "2026-09-25T09:00:00+00:00")
            put("updated_at", updatedAt)
            if (deletedAt != null) put("deleted_at", deletedAt) else put("deleted_at", JsonNull)
        }

    private fun stagedFile(
        id: String,
        deletedAt: Instant? = null,
    ) = FileItem(
        id = id,
        businessId = Fixtures.BUSINESS_ID,
        folderId = null,
        name = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 42,
        driveFileId = null,
        createdBy = Fixtures.USER_ID,
        createdAt = Fixtures.NOW,
        updatedAt = Fixtures.NOW,
        deletedAt = deletedAt,
    )

    @Test
    fun `sync specs describe the three files tables per the design contract`() {
        val folders = SyncTables.byName("folders")!!
        assertThat(folders.businessScoped).isTrue()
        assertThat(folders.cursorColumn).isEqualTo("updated_at")
        assertThat(folders.tombstoneCursorColumn).isNull()

        val files = SyncTables.byName("files")!!
        assertThat(files.localOnlyKeys).containsExactly("local_cache_path", "drive_permission_ensured")
        assertThat(files.tombstoneCursorColumn).isNull() // mutable rows: ONE pull leg

        val access = SyncTables.byName("folder_access")!!
        assertThat(access.idColumn).isEqualTo("folder_id")
        assertThat(access.idColumn2).isEqualTo("member_id")
        assertThat(access.entityIdOf(remoteAccessRow("f-1", "m-1", "2026-09-25T10:00:00+00:00"))).isEqualTo("f-1|m-1")
    }

    @Test
    fun `pulled folders files and access rows apply to Room`() =
        runTest {
            seedBusiness()
            remote.servePage("folders", listOf(remoteFolderRow("f-1", "2026-09-25T10:00:00+00:00", restricted = true)))
            remote.servePage("files", listOf(remoteFileRow("file-1", "2026-09-25T10:00:00+00:00")))
            remote.servePage("folder_access", listOf(remoteAccessRow("f-1", "m-1", "2026-09-25T10:00:00+00:00")))

            syncEngine(db, remote, notifier).runSync()

            assertThat(db.folderDao().byId("f-1")!!.restricted).isTrue()
            val file = db.fileDao().byId("file-1")!!
            assertThat(file.driveFileId).isEqualTo("drive-file-1")
            assertThat(file.sizeBytes).isEqualTo(1234L)
            assertThat(file.localCachePath).isNull()
            assertThat(db.folderAccessDao().byIds("f-1", "m-1")).isNotNull()
            assertThat(db.syncCursorDao().cursor(Fixtures.BUSINESS_ID, "folder_access"))
                .isEqualTo(
                    SyncCursorEntity(
                        Fixtures.BUSINESS_ID,
                        "folder_access",
                        Instant.parse("2026-09-25T10:00:00Z"),
                        "f-1|m-1",
                        "2026-09-25T10:00:00+00:00",
                    ),
                )
        }

    @Test
    fun `remote tombstones propagate through the single updated_at leg and preserve device-only columns`() =
        runTest {
            seedBusiness()
            // Device already has the live row with a cached copy + ensured flag.
            db.fileDao().upsert(
                FileEntity(
                    id = "file-1",
                    businessId = Fixtures.BUSINESS_ID,
                    name = "lease.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 1234,
                    driveFileId = "drive-file-1",
                    createdBy = Fixtures.USER_ID,
                    createdAt = Instant.parse("2026-09-25T09:00:00Z"),
                    updatedAt = Instant.parse("2026-09-25T10:00:00Z"),
                    localCachePath = "/cache/file-1",
                    drivePermissionEnsured = true,
                ),
            )
            db.folderDao().upsert(
                com.itsluminous.samaroh.core.database.entity.FolderEntity(
                    id = "f-1",
                    businessId = Fixtures.BUSINESS_ID,
                    name = "Contracts",
                    createdBy = Fixtures.USER_ID,
                    createdAt = Instant.parse("2026-09-25T09:00:00Z"),
                    updatedAt = Instant.parse("2026-09-25T10:00:00Z"),
                ),
            )
            db.folderAccessDao().upsert(
                com.itsluminous.samaroh.core.database.entity.FolderAccessEntity(
                    folderId = "f-1",
                    memberId = "m-1",
                    businessId = Fixtures.BUSINESS_ID,
                    createdAt = Instant.parse("2026-09-25T09:00:00Z"),
                    updatedAt = Instant.parse("2026-09-25T10:00:00Z"),
                ),
            )
            // Another device tombstoned all three (UPDATE deleted_at bumps updated_at).
            remote.servePage(
                "files",
                listOf(remoteFileRow("file-1", "2026-09-25T12:00:00+00:00", deletedAt = "2026-09-25T12:00:00+00:00")),
            )
            remote.servePage(
                "folders",
                listOf(remoteFolderRow("f-1", "2026-09-25T12:00:00+00:00", deletedAt = "2026-09-25T12:00:00+00:00")),
            )
            remote.servePage(
                "folder_access",
                listOf(remoteAccessRow("f-1", "m-1", "2026-09-25T12:00:00+00:00", deletedAt = "2026-09-25T12:00:00+00:00")),
            )

            syncEngine(db, remote, notifier).runSync()

            val file = db.fileDao().byId("file-1")!!
            assertThat(file.deletedAt).isEqualTo(Instant.parse("2026-09-25T12:00:00Z"))
            assertThat(file.localCachePath).isEqualTo("/cache/file-1")
            assertThat(file.drivePermissionEnsured).isTrue()
            assertThat(db.folderDao().byId("f-1")!!.deletedAt).isNotNull()
            assertThat(db.folderAccessDao().byIds("f-1", "m-1")!!.deletedAt).isNotNull()
            // The pull used ONE cursor column — no second tombstone leg for mutable tables.
            assertThat(remote.pullCursorColumns["files"].orEmpty().toSet()).containsExactly("updated_at")
            assertThat(db.syncCursorDao().cursor(Fixtures.BUSINESS_ID, "files#deleted_at")).isNull()
        }

    @Test
    fun `files upsert uploads to Drive first and pushes the row with the drive id`() =
        runTest {
            seedBusiness()
            val staged = stagedFile("file-1")
            db.fileDao().upsert(
                FileEntity(
                    id = staged.id,
                    businessId = staged.businessId,
                    name = staged.name,
                    mimeType = staged.mimeType,
                    sizeBytes = staged.sizeBytes,
                    createdBy = staged.createdBy,
                    createdAt = staged.createdAt,
                    updatedAt = staged.updatedAt,
                    localCachePath = "/staging/file-1",
                ),
            )
            db.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "files",
                    entityId = staged.id,
                    operation = "upsert",
                    payloadJson = testJson.encodeToString(FileItem.serializer(), staged),
                    createdAt = FIXED_NOW,
                ),
            )
            val uploader = FakeFilesUploader(FilesUploader.UploadResult.Uploaded("drive-abc"))

            syncEngine(db, remote, notifier, filesUploader = uploader).runSync()

            assertThat(uploader.uploaded).containsExactly("file-1")
            val pushed = remote.upserts.single { it.first == "files" }.second
            assertThat(pushed.getValue("drive_file_id").jsonPrimitive.content).isEqualTo("drive-abc")
            // Device-only keys never reach the wire; the Room row now carries the id.
            assertThat(pushed.keys).containsNoneOf("local_cache_path", "drive_permission_ensured")
            assertThat(db.fileDao().byId("file-1")!!.driveFileId).isEqualTo("drive-abc")
            assertThat(db.fileDao().byId("file-1")!!.localCachePath).isEqualTo("/staging/file-1")
            assertThat(db.outboxDao().pendingForEntity("files", "file-1")).isEmpty()
        }

    @Test
    fun `unlinked uploader keeps the files op queued and pushes nothing`() =
        runTest {
            seedBusiness()
            val staged = stagedFile("file-1")
            db.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "files",
                    entityId = staged.id,
                    operation = "upsert",
                    payloadJson = testJson.encodeToString(FileItem.serializer(), staged),
                    createdAt = FIXED_NOW,
                ),
            )

            syncEngine(db, remote, notifier, filesUploader = FakeFilesUploader(FilesUploader.UploadResult.NotLinked)).runSync()

            assertThat(remote.upserts.filter { it.first == "files" }).isEmpty()
            val pending = db.outboxDao().pendingForEntity("files", "file-1").single()
            assertThat(pending.lastError).isEqualTo(SyncEngine.ERROR_STORAGE_NOT_LINKED)
        }

    @Test
    fun `no uploader bound also holds the op instead of pushing a null drive id`() =
        runTest {
            seedBusiness()
            val staged = stagedFile("file-1")
            db.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "files",
                    entityId = staged.id,
                    operation = "upsert",
                    payloadJson = testJson.encodeToString(FileItem.serializer(), staged),
                    createdAt = FIXED_NOW,
                ),
            )

            syncEngine(db, remote, notifier).runSync()

            assertThat(remote.upserts.filter { it.first == "files" }).isEmpty()
            assertThat(db.outboxDao().pendingForEntity("files", "file-1")).hasSize(1)
        }

    @Test
    fun `a staged file tombstoned before upload is dropped without touching the server`() =
        runTest {
            seedBusiness()
            val gone = stagedFile("file-1", deletedAt = Instant.parse("2026-09-25T12:00:00Z"))
            db.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "files",
                    entityId = gone.id,
                    operation = "upsert",
                    payloadJson = testJson.encodeToString(FileItem.serializer(), gone),
                    createdAt = FIXED_NOW,
                ),
            )
            val uploader = FakeFilesUploader(FilesUploader.UploadResult.Uploaded("never"))

            val outcome = syncEngine(db, remote, notifier, filesUploader = uploader).runSync()

            assertThat(uploader.uploaded).isEmpty()
            assertThat(remote.upserts.filter { it.first == "files" }).isEmpty()
            assertThat(db.outboxDao().pendingForEntity("files", "file-1")).isEmpty()
            assertThat(outcome.itemErrorCount).isEqualTo(0)
        }

    @Test
    fun `uploaded file tombstone pushes as a whole-row upsert carrying deleted_at`() =
        runTest {
            seedBusiness()
            val tombstone = stagedFile("file-1", deletedAt = Instant.parse("2026-09-25T12:00:00Z")).copy(driveFileId = "drive-1")
            db.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "files",
                    entityId = tombstone.id,
                    operation = "upsert",
                    payloadJson = testJson.encodeToString(FileItem.serializer(), tombstone),
                    createdAt = FIXED_NOW,
                ),
            )

            syncEngine(db, remote, notifier).runSync()

            val pushed = remote.upserts.single { it.first == "files" }.second
            assertThat(pushed.getValue("deleted_at").jsonPrimitive.content).isNotEmpty()
            assertThat(pushed.getValue("drive_file_id").jsonPrimitive.content).isEqualTo("drive-1")
        }
}
