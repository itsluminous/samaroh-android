package com.itsluminous.samaroh.core.database

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.entity.FileEntity
import com.itsluminous.samaroh.core.database.entity.FolderEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** FILES DAOs (ADR-085): live-only listing, case-insensitive sibling steering, repair queue. */
@RunWith(RobolectricTestRunner::class)
class FilesDaoTest {
    private lateinit var db: SamarohDatabase
    private val now = Instant.parse("2026-09-25T10:00:00Z")

    @Before
    fun setUp() {
        db = testDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun folder(
        id: String,
        name: String,
        parentId: String? = null,
        deletedAt: Instant? = null,
    ) = FolderEntity(id, "biz", parentId, name, false, "u-1", null, now, now, deletedAt)

    private fun file(
        id: String,
        driveFileId: String? = null,
        ensured: Boolean = false,
        deletedAt: Instant? = null,
    ) = FileEntity(
        id = id,
        businessId = "biz",
        name = "a.pdf",
        mimeType = "application/pdf",
        sizeBytes = 1,
        driveFileId = driveFileId,
        createdBy = "u-1",
        createdAt = now,
        updatedAt = now,
        deletedAt = deletedAt,
        drivePermissionEnsured = ensured,
    )

    @Test
    fun `live sibling lookup is case-insensitive, parent-scoped and ignores tombstones`() =
        runTest {
            db.folderDao().upsert(folder("f-1", "Contracts"))
            db.folderDao().upsert(folder("f-2", "Contracts", parentId = "f-1"))
            db.folderDao().upsert(folder("f-3", "Photos", deletedAt = now))

            assertThat(db.folderDao().liveSiblingNamed("biz", null, "contracts")!!.id).isEqualTo("f-1")
            assertThat(db.folderDao().liveSiblingNamed("biz", "f-1", "CONTRACTS")!!.id).isEqualTo("f-2")
            assertThat(db.folderDao().liveSiblingNamed("biz", "f-2", "Contracts")).isNull()
            // A tombstoned twin never blocks re-creation (ADR-083 lesson).
            assertThat(db.folderDao().liveSiblingNamed("biz", null, "photos")).isNull()
            assertThat(
                db
                    .folderDao()
                    .foldersForBusiness("biz")
                    .first()
                    .map { it.id },
            ).containsExactly("f-1", "f-2")
        }

    @Test
    fun `permission repair queue lists only uploaded, unensured, live files`() =
        runTest {
            db.fileDao().upsert(file("staged"))
            db.fileDao().upsert(file("pending", driveFileId = "d-1"))
            db.fileDao().upsert(file("done", driveFileId = "d-2", ensured = true))
            db.fileDao().upsert(file("gone", driveFileId = "d-3", deletedAt = now))

            assertThat(db.fileDao().pendingPermissionRepair(10).map { it.id }).containsExactly("pending")
            db.fileDao().markDrivePermissionEnsured("pending")
            assertThat(db.fileDao().pendingPermissionRepair(10)).isEmpty()
            assertThat(
                db
                    .fileDao()
                    .filesForBusiness("biz")
                    .first()
                    .map { it.id },
            ).containsExactly("staged", "pending", "done")
        }
}
