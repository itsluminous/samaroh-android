package com.itsluminous.samaroh.core.google.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.image.ItemPhotoStorageDownloader
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
 * ADR-055/058: the Drive item-photo mirror — pending selection (Storage path, local file
 * OR storage download), §9.1 upload target, human-readable naming, the per-run storage
 * download throttle, Room + outbox stamping, and silent not-linked/failure behavior.
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
    private lateinit var downloader: FakeStorageDownloader
    private lateinit var outbox: RecordingOutboxWriter
    private lateinit var mirror: DriveItemImageMirror

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = inMemoryDatabase(context)
        uploader = RecordingDriveUploader()
        downloader = FakeStorageDownloader()
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
            downloader,
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
        imagePath: String? = "biz-1/$id/1.webp",
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
    fun `mirrors a pending local photo and stamps the drive id in Room and the outbox`() =
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
            // A local file means no storage download was needed.
            assertThat(downloader.requests).isEmpty()
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
    fun `a storage-only photo with no local file downloads and mirrors (ADR-058)`() =
        runTest {
            seedBusiness()
            db.masterItemDao().upsert(itemRow())

            val mirrored = mirror.mirrorPending()

            assertThat(mirrored).isEqualTo(1)
            assertThat(downloader.requests).containsExactly("biz-1/item-1/1.webp")
            val call = uploader.calls.single()
            assertThat(call.fileName).isEqualTo("Steel Plate-$stamp.webp")
            assertThat(call.sourceBytes).isEqualTo(FakeStorageDownloader.BYTES)
            assertThat(db.masterItemDao().byId("item-1")?.driveImageId).isEqualTo("drive-file-1")
            assertThat(outbox.entries).hasSize(1)
        }

    @Test
    fun `a failed storage download leaves the row pending and mirrors the rest`() =
        runTest {
            seedBusiness()
            db.masterItemDao().upsert(itemRow(id = "item-1", name = "Broken"))
            db.masterItemDao().upsert(itemRow(id = "item-2", name = "Fine"))
            downloader.result = { path ->
                if (path.contains("item-1")) {
                    Result.failure(IOException("offline"))
                } else {
                    Result.success(FakeStorageDownloader.BYTES.toByteArray())
                }
            }

            assertThat(mirror.mirrorPending()).isEqualTo(1)
            assertThat(db.masterItemDao().byId("item-1")?.driveImageId).isNull()
            assertThat(db.masterItemDao().byId("item-2")?.driveImageId).isEqualTo("drive-file-1")
        }

    @Test
    fun `storage downloads are throttled per run - the next run continues`() =
        runTest {
            seedBusiness()
            val total = DriveItemImageMirror.MAX_STORAGE_DOWNLOADS_PER_RUN + 2
            repeat(total) { i ->
                db.masterItemDao().upsert(itemRow(id = "item-$i", name = "Item $i", imagePath = "biz-1/item-$i/1.webp"))
            }

            assertThat(mirror.mirrorPending()).isEqualTo(DriveItemImageMirror.MAX_STORAGE_DOWNLOADS_PER_RUN)
            assertThat(downloader.requests).hasSize(DriveItemImageMirror.MAX_STORAGE_DOWNLOADS_PER_RUN)

            // Next sync run: the remaining rows mirror (already-stamped rows are excluded).
            assertThat(mirror.mirrorPending()).isEqualTo(2)
            assertThat(db.masterItemDao().pendingDriveImageMirror()).isEmpty()
        }

    @Test
    fun `a local-file mirror is not counted against the download throttle`() =
        runTest {
            seedBusiness()
            repeat(DriveItemImageMirror.MAX_STORAGE_DOWNLOADS_PER_RUN) { i ->
                db.masterItemDao().upsert(itemRow(id = "item-s$i", name = "Storage $i", imagePath = "biz-1/item-s$i/1.webp"))
            }
            db.masterItemDao().upsert(itemRow(id = "item-1", name = "Local One"))
            writeLocalFile("item-1")

            // All storage rows use the budget AND the local-file row still mirrors.
            assertThat(mirror.mirrorPending()).isEqualTo(DriveItemImageMirror.MAX_STORAGE_DOWNLOADS_PER_RUN + 1)
        }

    @Test
    fun `names sanitize for Drive and collide-uniquify within the same second`() =
        runTest {
            seedBusiness()
            // Distinct row names (the DAO enforces per-business uniqueness) that sanitize
            // to the SAME base, mirrored under a fixed clock = the same-second collision.
            db.masterItemDao().upsert(itemRow(id = "item-a", name = "Steel/Plate"))
            db.masterItemDao().upsert(itemRow(id = "item-b", name = "Steel: Plate"))

            assertThat(mirror.mirrorPending()).isEqualTo(2)
            assertThat(uploader.calls.map { it.fileName })
                .containsExactly("Steel Plate-$stamp.webp", "Steel Plate-$stamp-2.webp")
                .inOrder()
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

private class FakeStorageDownloader : ItemPhotoStorageDownloader {
    val requests = mutableListOf<String>()

    var result: (String) -> Result<ByteArray> = { Result.success(BYTES.toByteArray()) }

    override suspend fun download(objectPath: String): Result<ByteArray> {
        requests += objectPath
        return result(objectPath)
    }

    companion object {
        val BYTES = listOf<Byte>(9, 8, 7)
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
