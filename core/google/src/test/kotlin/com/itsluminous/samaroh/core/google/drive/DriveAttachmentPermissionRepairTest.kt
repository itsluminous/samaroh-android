package com.itsluminous.samaroh.core.google.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.ExpenseAttachmentEntity
import com.itsluminous.samaroh.core.database.entity.MasterItemEntity
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

/**
 * ADR-059 retroactive repair (item photos added by ADR-063): bills AND item photos
 * uploaded before the link-permission change (or whose inline permission call failed)
 * gain the anyone-with-link reader permission on the uploader's device, N per set per
 * sync run, idempotently; definitive not-my-file answers stop this device retrying
 * without ever syncing that verdict.
 */
@RunWith(RobolectricTestRunner::class)
class DriveAttachmentPermissionRepairTest {
    private val now = Instant.parse("2026-09-07T06:00:00Z")

    private lateinit var context: Context
    private lateinit var db: SamarohDatabase
    private lateinit var driveService: ScriptedDriveService
    private lateinit var repair: DriveAttachmentPermissionRepair

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = inMemoryDatabase(context)
        driveService = ScriptedDriveService()
        repair = DriveAttachmentPermissionRepair(db.expenseAttachmentDao(), db.masterItemDao(), driveService)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedRow(
        id: String,
        driveFileId: String? = "drive-$id",
        ensured: Boolean = false,
        deletedAt: Instant? = null,
        createdAt: Instant = now,
    ) {
        db.expenseAttachmentDao().upsert(
            ExpenseAttachmentEntity(
                id = id,
                expenseId = "exp-1",
                businessId = "biz-1",
                driveFileId = driveFileId,
                mimeType = "image/jpeg",
                fileName = "$id.jpg",
                drivePermissionEnsured = ensured,
                createdAt = createdAt,
                deletedAt = deletedAt,
            ),
        )
    }

    private suspend fun seedItem(
        id: String,
        driveImageId: String? = "drive-$id",
        ensured: Boolean = false,
        deletedAt: Instant? = null,
        updatedAt: Instant = now,
    ) {
        db.masterItemDao().upsert(
            MasterItemEntity(
                id = id,
                businessId = "biz-1",
                name = "Item $id",
                unit = "pcs",
                imagePath = null,
                driveImageId = driveImageId,
                drivePermissionEnsured = ensured,
                createdAt = now,
                updatedAt = updatedAt,
                deletedAt = deletedAt,
            ),
        )
    }

    @Test
    fun `repairs pending rows and marks the local flag`() =
        runTest {
            seedRow("att-1")
            seedRow("att-2")

            val settled = repair.repairPending()

            assertThat(settled).isEqualTo(2)
            assertThat(driveService.ensuredFileIds).containsExactly("drive-att-1", "drive-att-2")
            assertThat(db.expenseAttachmentDao().byId("att-1")?.drivePermissionEnsured).isTrue()
            assertThat(db.expenseAttachmentDao().byId("att-2")?.drivePermissionEnsured).isTrue()
        }

    @Test
    fun `already-ensured, tombstoned and never-uploaded rows are skipped`() =
        runTest {
            seedRow("att-done", ensured = true)
            seedRow("att-dead", deletedAt = now)
            seedRow("att-local", driveFileId = null)

            assertThat(repair.repairPending()).isEqualTo(0)
            assertThat(driveService.ensuredFileIds).isEmpty()
        }

    @Test
    fun `second run is a no-op after a successful repair (idempotent tracking)`() =
        runTest {
            seedRow("att-1")

            repair.repairPending()
            driveService.ensuredFileIds.clear()

            assertThat(repair.repairPending()).isEqualTo(0)
            assertThat(driveService.ensuredFileIds).isEmpty()
        }

    @Test
    fun `throttles to the per-run budget, oldest first, and the next run continues`() =
        runTest {
            repeat(DriveAttachmentPermissionRepair.MAX_REPAIRS_PER_RUN + 3) { index ->
                seedRow("att-$index", createdAt = now.plusSeconds(index.toLong()))
            }

            assertThat(repair.repairPending()).isEqualTo(DriveAttachmentPermissionRepair.MAX_REPAIRS_PER_RUN)
            assertThat(driveService.ensuredFileIds.first()).isEqualTo("drive-att-0")

            assertThat(repair.repairPending()).isEqualTo(3)
        }

    @Test
    fun `repairs pending item photos too and marks their local flag (ADR-063)`() =
        runTest {
            seedRow("att-1")
            seedItem("item-1")
            seedItem("item-done", ensured = true)
            seedItem("item-dead", deletedAt = now)
            seedItem("item-unmirrored", driveImageId = null)

            val settled = repair.repairPending()

            assertThat(settled).isEqualTo(2)
            assertThat(driveService.ensuredFileIds).containsExactly("drive-att-1", "drive-item-1")
            assertThat(db.masterItemDao().byId("item-1")?.drivePermissionEnsured).isTrue()
            // Idempotent tracking: a second run touches nothing.
            driveService.ensuredFileIds.clear()
            assertThat(repair.repairPending()).isEqualTo(0)
        }

    @Test
    fun `item-photo throttle is its own budget, oldest first`() =
        runTest {
            repeat(DriveAttachmentPermissionRepair.MAX_REPAIRS_PER_RUN + 2) { index ->
                seedItem("item-$index", updatedAt = now.plusSeconds(index.toLong()))
            }

            assertThat(repair.repairPending()).isEqualTo(DriveAttachmentPermissionRepair.MAX_REPAIRS_PER_RUN)
            assertThat(driveService.ensuredFileIds.first()).isEqualTo("drive-item-0")
            assertThat(repair.repairPending()).isEqualTo(2)
        }

    @Test
    fun `not-my-file item photo (403) marks the local flag so this device stops retrying`() =
        runTest {
            seedItem("item-theirs")
            driveService.errorsByFileId["drive-item-theirs"] = GoogleApiException(403, "forbidden")

            assertThat(repair.repairPending()).isEqualTo(1)
            assertThat(db.masterItemDao().byId("item-theirs")?.drivePermissionEnsured).isTrue()
        }

    @Test
    fun `not-my-file (404) marks the local flag so this device stops retrying`() =
        runTest {
            seedRow("att-theirs")
            driveService.errorsByFileId["drive-att-theirs"] = GoogleApiException(404, "not found")

            assertThat(repair.repairPending()).isEqualTo(1)
            assertThat(db.expenseAttachmentDao().byId("att-theirs")?.drivePermissionEnsured).isTrue()
        }

    @Test
    fun `transient failure leaves the row pending for the next run`() =
        runTest {
            seedRow("att-flaky")
            driveService.errorsByFileId["drive-att-flaky"] = GoogleApiException(500, "server error")

            assertThat(repair.repairPending()).isEqualTo(0)
            assertThat(db.expenseAttachmentDao().byId("att-flaky")?.drivePermissionEnsured).isFalse()

            driveService.errorsByFileId.clear()
            assertThat(repair.repairPending()).isEqualTo(1)
        }

    @Test
    fun `not linked stops the whole pass silently`() =
        runTest {
            seedRow("att-1")
            seedRow("att-2")
            driveService.errorsByFileId["drive-att-1"] = DriveNotAvailableException("not linked")

            assertThat(repair.repairPending()).isEqualTo(0)
            assertThat(db.expenseAttachmentDao().byId("att-2")?.drivePermissionEnsured).isFalse()
        }
}

private class ScriptedDriveService : DriveService {
    val ensuredFileIds = mutableListOf<String>()
    val errorsByFileId = mutableMapOf<String, Exception>()

    override suspend fun ensureAnyoneReaderPermission(fileId: String) {
        errorsByFileId[fileId]?.let { throw it }
        ensuredFileIds += fileId
    }

    override suspend fun findFolder(
        name: String,
        parentId: String?,
    ): String? = null

    override suspend fun createFolder(
        name: String,
        parentId: String?,
    ): String = "folder-id"

    override suspend fun uploadFile(
        name: String,
        mimeType: String,
        parentId: String,
        sourceFile: File,
    ): DriveFileRef = DriveFileRef("file-id", name)

    override suspend fun downloadFile(
        fileId: String,
        target: File,
    ) = Unit

    override suspend fun downloadPublicFile(
        fileId: String,
        target: File,
    ) = Unit

    override suspend fun deleteFile(fileId: String) = Unit
}
