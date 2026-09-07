package com.itsluminous.samaroh.core.google.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.image.localItemImageFile
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.BusinessEntity
import com.itsluminous.samaroh.core.database.entity.MasterItemEntity
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * ADR-055/063: the Drive item-photo upload — pending selection (device-local bytes
 * only: the row's local `image_path` or the legacy `{itemId}.webp` original), §9.1
 * upload target, human-readable naming, inline anyone-with-link sharing, Room + outbox
 * stamping, and silent not-linked/failure behavior.
 */
@RunWith(RobolectricTestRunner::class)
class DriveItemImageMirrorTest {
    private val now = Instant.parse("2026-09-07T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** [now] rendered by [DriveNameFactory]'s pattern in the test's fixed UTC zone. */
    private val stamp = "20260907-060000"

    private lateinit var context: Context
    private lateinit var db: SamarohDatabase
    private lateinit var uploader: RecordingDriveUploader
    private lateinit var driveService: RecordingPermissionDriveService
    private lateinit var outbox: RecordingOutboxWriter
    private lateinit var mirror: DriveItemImageMirror

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = inMemoryDatabase(context)
        uploader = RecordingDriveUploader()
        driveService = RecordingPermissionDriveService()
        outbox = RecordingOutboxWriter()
        mirror = newMirror()
    }

    /** A fresh mirror = a fresh [DriveNameFactory] (its uniquifier state is per-instance). */
    private fun newMirror() =
        DriveItemImageMirror(
            context,
            db.masterItemDao(),
            db.businessDao(),
            uploader,
            driveService,
            DriveNameFactory(clock, ZoneOffset.UTC),
            outbox,
            clock,
        )

    @After
    fun tearDown() {
        db.close()
        localItemImageFile(context, "item-1").delete()
    }

    private suspend fun seedBusiness() {
        db.businessDao().upsert(
            BusinessEntity(
                id = "biz-1",
                name = "Sharma Hall",
                ownerName = "owner-fixture",
                ownerUserId = "user-1",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun itemRow(
        id: String = "item-1",
        name: String = "Steel Plate",
        imagePath: String? = localItemImageFile(context, id).absolutePath,
        driveImageId: String? = null,
    ) = MasterItemEntity(
        id = id,
        businessId = "biz-1",
        name = name,
        unit = "pcs",
        imagePath = imagePath,
        driveImageId = driveImageId,
        createdAt = now,
        updatedAt = now,
    )

    private fun writeLocalFile(itemId: String): File =
        localItemImageFile(context, itemId).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

    @Test
    fun `uploads a pending local photo, shares it and stamps the drive id`() =
        runTest {
            seedBusiness()
            db.masterItemDao().upsert(itemRow())
            writeLocalFile("item-1")

            val mirrored = mirror.mirrorPending()

            assertThat(mirrored).isEqualTo(1)
            val call = uploader.calls.single()
            assertThat(call.businessName).isEqualTo("Sharma Hall")
            assertThat(call.target).isEqualTo(DriveTarget.InventoryImages)
            assertThat(call.fileName).isEqualTo("Steel Plate-$stamp.webp")
            assertThat(call.mimeType).isEqualTo("image/webp")
            // ADR-063: members/web serve from Drive, so the file is shared like a bill.
            assertThat(driveService.ensuredPermissions).containsExactly("drive-file-1")
            // Room converged (drive id + device-only permission flag)...
            val row = db.masterItemDao().byId("item-1")
            assertThat(row?.driveImageId).isEqualTo("drive-file-1")
            assertThat(row?.drivePermissionEnsured).isTrue()
            // ...and the row upsert (carrying drive_image_id) is queued for sync.
            val enqueued = outbox.entries.single()
            assertThat(enqueued.entityType).isEqualTo("master_items")
            assertThat(enqueued.operation).isEqualTo(OutboxOperation.UPSERT)
            assertThat(enqueued.payloadJson).contains("\"drive_image_id\":\"drive-file-1\"")
        }

    @Test
    fun `a failed inline permission does not fail the upload - flag stays pending`() =
        runTest {
            seedBusiness()
            db.masterItemDao().upsert(itemRow())
            writeLocalFile("item-1")
            driveService.permissionResult = { Result.failure(IOException("offline")) }

            assertThat(mirror.mirrorPending()).isEqualTo(1)
            val row = db.masterItemDao().byId("item-1")
            assertThat(row?.driveImageId).isEqualTo("drive-file-1")
            // The repair pass (ADR-059/063) will retry this file.
            assertThat(row?.drivePermissionEnsured).isFalse()
            assertThat(db.masterItemDao().pendingDrivePermissionRepair(10).map { it.id })
                .containsExactly("item-1")
        }

    @Test
    fun `a legacy storage-path row with the original on disk still uploads`() =
        runTest {
            // Photos taken pre-ADR-063: image_path was rewritten to a Storage object
            // path, but the {itemId}.webp original is still on this device.
            seedBusiness()
            db.masterItemDao().upsert(itemRow(imagePath = "biz-1/item-1/1.webp"))
            writeLocalFile("item-1")

            assertThat(mirror.mirrorPending()).isEqualTo(1)
            assertThat(uploader.calls.single().sourceBytes).isEqualTo(listOf<Byte>(1, 2, 3))
            assertThat(db.masterItemDao().byId("item-1")?.driveImageId).isEqualTo("drive-file-1")
        }

    @Test
    fun `a row with no local bytes is skipped - storage is gone (ADR-063)`() =
        runTest {
            seedBusiness()
            db.masterItemDao().upsert(itemRow(imagePath = "biz-1/item-1/1.webp"))

            assertThat(mirror.mirrorPending()).isEqualTo(0)
            assertThat(uploader.calls).isEmpty()
            assertThat(outbox.entries).isEmpty()
        }

    @Test
    fun `a local image path whose file vanished is skipped`() =
        runTest {
            seedBusiness()
            db.masterItemDao().upsert(itemRow())

            assertThat(mirror.mirrorPending()).isEqualTo(0)
            assertThat(uploader.calls).isEmpty()
        }

    @Test
    fun `names sanitize for Drive and collide-uniquify within the same second`() =
        runTest {
            seedBusiness()
            // Distinct row names (the DAO enforces per-business uniqueness) that sanitize
            // to the SAME base, mirrored under a fixed clock = the same-second collision.
            db.masterItemDao().upsert(itemRow(id = "item-a", name = "Steel/Plate", imagePath = writeLocalFile("item-a").absolutePath))
            db.masterItemDao().upsert(itemRow(id = "item-b", name = "Steel: Plate", imagePath = writeLocalFile("item-b").absolutePath))

            assertThat(mirror.mirrorPending()).isEqualTo(2)
            assertThat(uploader.calls.map { it.fileName })
                .containsExactly("Steel Plate-$stamp.webp", "Steel Plate-$stamp-2.webp")
                .inOrder()
            localItemImageFile(context, "item-a").delete()
            localItemImageFile(context, "item-b").delete()
        }

    @Test
    fun `a row that already has a drive id is not re-uploaded`() =
        runTest {
            seedBusiness()
            db.masterItemDao().upsert(itemRow(driveImageId = "drive-old"))
            writeLocalFile("item-1")

            assertThat(mirror.mirrorPending()).isEqualTo(0)
            assertThat(uploader.calls).isEmpty()
        }

    @Test
    fun `not linked stops the pass silently - everything stays pending`() =
        runTest {
            seedBusiness()
            db.masterItemDao().upsert(itemRow())
            writeLocalFile("item-1")
            uploader.result = { Result.failure(DriveNotAvailableException("no google account linked")) }

            assertThat(mirror.mirrorPending()).isEqualTo(0)
            assertThat(db.masterItemDao().byId("item-1")?.driveImageId).isNull()
            assertThat(outbox.entries).isEmpty()
        }

    @Test
    fun `a transient failure leaves the row pending for the next run`() =
        runTest {
            seedBusiness()
            db.masterItemDao().upsert(itemRow())
            writeLocalFile("item-1")
            uploader.result = { Result.failure(IOException("offline")) }

            assertThat(mirror.mirrorPending()).isEqualTo(0)
            assertThat(db.masterItemDao().byId("item-1")?.driveImageId).isNull()
            // Still pending: a later run with a working uploader mirrors it.
            uploader.result = null
            assertThat(mirror.mirrorPending()).isEqualTo(1)
        }
}

private class RecordingDriveUploader : DriveUploader {
    data class Call(
        val businessName: String,
        val target: DriveTarget,
        val fileName: String,
        val mimeType: String,
        val sourceBytes: List<Byte>,
    )

    val calls = mutableListOf<Call>()

    /** Override to fail; null = succeed with a deterministic file id. */
    var result: (() -> Result<DriveFileRef>)? = null

    override suspend fun upload(
        businessName: String,
        target: DriveTarget,
        fileName: String,
        mimeType: String,
        sourceFile: File,
    ): Result<DriveFileRef> {
        val failure = result?.invoke()
        if (failure != null) return failure
        calls += Call(businessName, target, fileName, mimeType, sourceFile.readBytes().toList())
        return Result.success(DriveFileRef(fileId = "drive-file-${calls.size}", fileName = fileName))
    }
}

/** Records permission calls; every other [DriveService] operation is unreachable here. */
private class RecordingPermissionDriveService : DriveService {
    val ensuredPermissions = mutableListOf<String>()

    var permissionResult: (() -> Result<Unit>)? = null

    override suspend fun ensureAnyoneReaderPermission(fileId: String) {
        permissionResult?.invoke()?.getOrThrow()
        ensuredPermissions += fileId
    }

    override suspend fun findFolder(
        name: String,
        parentId: String?,
    ): String? = throw UnsupportedOperationException()

    override suspend fun createFolder(
        name: String,
        parentId: String?,
    ): String = throw UnsupportedOperationException()

    override suspend fun uploadFile(
        name: String,
        mimeType: String,
        parentId: String,
        sourceFile: File,
    ): DriveFileRef = throw UnsupportedOperationException()

    override suspend fun downloadFile(
        fileId: String,
        target: File,
    ): Unit = throw UnsupportedOperationException()

    override suspend fun downloadPublicFile(
        fileId: String,
        target: File,
    ): Unit = throw UnsupportedOperationException()

    override suspend fun deleteFile(fileId: String): Unit = throw UnsupportedOperationException()
}

private class RecordingOutboxWriter : OutboxWriter {
    data class Entry(
        val entityType: String,
        val entityId: String,
        val operation: OutboxOperation,
        val payloadJson: String,
    )

    val entries = mutableListOf<Entry>()

    override suspend fun enqueue(
        entityType: String,
        entityId: String,
        operation: OutboxOperation,
        payloadJson: String,
    ) {
        entries += Entry(entityType, entityId, operation, payloadJson)
    }
}
