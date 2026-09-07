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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * ADR-055: the Drive item-photo mirror — pending selection (Storage path + local file),
 * §9.1 upload target, Room + outbox stamping, and silent not-linked/failure behavior.
 */
@RunWith(RobolectricTestRunner::class)
class DriveItemImageMirrorTest {
    private val now = Instant.parse("2026-09-07T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private lateinit var context: Context
    private lateinit var db: SamarohDatabase
    private lateinit var uploader: RecordingDriveUploader
    private lateinit var outbox: RecordingOutboxWriter
    private lateinit var mirror: DriveItemImageMirror

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = inMemoryDatabase(context)
        uploader = RecordingDriveUploader()
        outbox = RecordingOutboxWriter()
        mirror = DriveItemImageMirror(context, db.masterItemDao(), db.businessDao(), uploader, outbox, clock)
    }

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
        imagePath: String? = "biz-1/$id/1.webp",
        driveImageId: String? = null,
    ) = MasterItemEntity(
        id = id,
        businessId = "biz-1",
        name = "Steel Plate",
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
    fun `mirrors a pending photo and stamps the drive id in Room and the outbox`() =
        runTest {
            seedBusiness()
            db.masterItemDao().upsert(itemRow())
            writeLocalFile("item-1")

            val mirrored = mirror.mirrorPending()

            assertThat(mirrored).isEqualTo(1)
            val call = uploader.calls.single()
            assertThat(call.businessName).isEqualTo("Sharma Hall")
            assertThat(call.target).isEqualTo(DriveTarget.InventoryImages)
            assertThat(call.fileName).isEqualTo("Steel Plate-item-1.webp")
            assertThat(call.mimeType).isEqualTo("image/webp")
            // Room converged...
            assertThat(db.masterItemDao().byId("item-1")?.driveImageId).isEqualTo("drive-file-1")
            // ...and the row upsert (carrying drive_image_id) is queued for sync.
            val enqueued = outbox.entries.single()
            assertThat(enqueued.entityType).isEqualTo("master_items")
            assertThat(enqueued.operation).isEqualTo(OutboxOperation.UPSERT)
            assertThat(enqueued.payloadJson).contains("\"drive_image_id\":\"drive-file-1\"")
        }

    @Test
    fun `a still-local image path is not mirrored - storage upload goes first`() =
        runTest {
            seedBusiness()
            db.masterItemDao().upsert(itemRow(imagePath = "/data/user/0/app/files/inventory-images/item-1.webp"))
            writeLocalFile("item-1")

            assertThat(mirror.mirrorPending()).isEqualTo(0)
            assertThat(uploader.calls).isEmpty()
            assertThat(outbox.entries).isEmpty()
        }

    @Test
    fun `a web-added photo with no local file is skipped`() =
        runTest {
            seedBusiness()
            db.masterItemDao().upsert(itemRow())

            assertThat(mirror.mirrorPending()).isEqualTo(0)
            assertThat(uploader.calls).isEmpty()
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
            uploader.result = { Result.failure(java.io.IOException("offline")) }

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
        calls += Call(businessName, target, fileName, mimeType)
        return Result.success(DriveFileRef(fileId = "drive-file-${calls.size}", fileName = fileName))
    }
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
