package com.itsluminous.samaroh.core.google.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.AttachmentUploader
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.BusinessEntity
import com.itsluminous.samaroh.core.database.entity.ExpenseAttachmentEntity
import com.itsluminous.samaroh.core.database.entity.ExpenseEntity
import com.itsluminous.samaroh.core.database.entity.PartyEntity
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
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
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * ADR-058: expense attachments upload to Drive under a human-readable
 * `{party name}-{yyyyMMdd-HHmmss}.{ext}` name (sanitized, same-second uniquified) while
 * the Room row's `file_name` stays what the app displays. Target folder + not-linked
 * behavior are unchanged from ADR-018.
 */
@RunWith(RobolectricTestRunner::class)
class DriveAttachmentUploaderTest {
    private val now = Instant.parse("2026-09-07T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val stamp = "20260907-060000"

    private lateinit var context: Context
    private lateinit var db: SamarohDatabase
    private lateinit var drive: RecordingUploader
    private lateinit var driveService: PermissionRecordingDriveService
    private lateinit var uploader: DriveAttachmentUploader
    private lateinit var localFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = inMemoryDatabase(context)
        drive = RecordingUploader()
        driveService = PermissionRecordingDriveService()
        uploader =
            DriveAttachmentUploader(
                db.expenseAttachmentDao(),
                db.expenseDao(),
                db.partyDao(),
                db.businessDao(),
                drive,
                driveService,
                DriveNameFactory(clock, ZoneOffset.UTC),
            )
        localFile = File(context.cacheDir, "att-src.jpg").apply { writeBytes(byteArrayOf(1)) }
    }

    @After
    fun tearDown() {
        db.close()
        localFile.delete()
    }

    private suspend fun seed(partyName: String = "Sharma Caterers") {
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
        db.partyDao().upsert(
            PartyEntity(id = "party-1", businessId = "biz-1", name = partyName, createdAt = now, updatedAt = now),
        )
        db.expenseDao().upsert(
            ExpenseEntity(
                id = "exp-1",
                businessId = "biz-1",
                partyId = "party-1",
                amountPaise = 500_00L,
                expenseDate = LocalDate.of(2026, 9, 7),
                createdBy = "user-1",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun seedAttachment(
        id: String = "att-1",
        fileName: String = "bill-1a2b3c4d.jpg",
        mimeType: String = "image/jpeg",
    ) {
        db.expenseAttachmentDao().upsert(
            ExpenseAttachmentEntity(
                id = id,
                expenseId = "exp-1",
                businessId = "biz-1",
                mimeType = mimeType,
                fileName = fileName,
                localCachePath = localFile.absolutePath,
                createdAt = now,
            ),
        )
    }

    @Test
    fun `drive name is party plus timestamp, not the stored file name`() =
        runTest {
            seed()
            seedAttachment()

            val result = uploader.upload("att-1")

            assertThat(result).isInstanceOf(AttachmentUploader.UploadResult.Uploaded::class.java)
            val call = drive.calls.single()
            assertThat(call.fileName).isEqualTo("Sharma Caterers-$stamp.jpg")
            assertThat(call.target).isEqualTo(DriveTarget.ExpenseInvoices("Sharma Caterers"))
            assertThat(call.mimeType).isEqualTo("image/jpeg")
            // The Room row keeps its display name — no rename of local metadata.
            assertThat(db.expenseAttachmentDao().byId("att-1")?.fileName).isEqualTo("bill-1a2b3c4d.jpg")
        }

    @Test
    fun `extension follows the stored file name, mime type is the fallback`() =
        runTest {
            seed()
            seedAttachment(id = "att-pdf", fileName = "invoice-9f8e.pdf", mimeType = "application/pdf")
            seedAttachment(id = "att-noext", fileName = "scan", mimeType = "application/pdf")

            uploader.upload("att-pdf")
            uploader.upload("att-noext")

            assertThat(drive.calls[0].fileName).isEqualTo("Sharma Caterers-$stamp.pdf")
            // Same party + same second: the second upload also uniquifies.
            assertThat(drive.calls[1].fileName).isEqualTo("Sharma Caterers-$stamp-2.pdf")
        }

    @Test
    fun `party names sanitize for Drive`() =
        runTest {
            seed(partyName = "A/C: Tent *House*")
            seedAttachment()

            uploader.upload("att-1")

            assertThat(drive.calls.single().fileName).isEqualTo("A C Tent House-$stamp.jpg")
        }

    @Test
    fun `two attachments to the same party in the same second uniquify`() =
        runTest {
            seed()
            seedAttachment(id = "att-1")
            seedAttachment(id = "att-2", fileName = "bill-99ff00aa.jpg")

            uploader.upload("att-1")
            uploader.upload("att-2")

            assertThat(drive.calls.map { it.fileName })
                .containsExactly("Sharma Caterers-$stamp.jpg", "Sharma Caterers-$stamp-2.jpg")
                .inOrder()
        }

    @Test
    fun `not linked maps to NotLinked and nothing is stamped`() =
        runTest {
            seed()
            seedAttachment()
            drive.result = { Result.failure(DriveNotAvailableException("not linked")) }

            assertThat(uploader.upload("att-1")).isEqualTo(AttachmentUploader.UploadResult.NotLinked)
            assertThat(db.expenseAttachmentDao().byId("att-1")?.driveFileId).isNull()
        }

    @Test
    fun `successful upload ensures the anyone-with-link permission and marks the flag`() =
        runTest {
            seed()
            seedAttachment()

            val result = uploader.upload("att-1")

            assertThat(result).isInstanceOf(AttachmentUploader.UploadResult.Uploaded::class.java)
            assertThat(driveService.permissionEnsuredFileIds).containsExactly("drive-file-1")
            assertThat(db.expenseAttachmentDao().byId("att-1")?.drivePermissionEnsured).isTrue()
        }

    @Test
    fun `permission failure is best-effort - upload still succeeds, flag stays pending for repair`() =
        runTest {
            seed()
            seedAttachment()
            driveService.ensureError = GoogleApiException(500, "boom")

            val result = uploader.upload("att-1")

            assertThat(result).isInstanceOf(AttachmentUploader.UploadResult.Uploaded::class.java)
            assertThat(db.expenseAttachmentDao().byId("att-1")?.drivePermissionEnsured).isFalse()
        }
}

/** Records [ensureAnyoneReaderPermission] calls; other members are unused by the uploader. */
internal class PermissionRecordingDriveService : DriveService {
    val permissionEnsuredFileIds = mutableListOf<String>()

    /** When set, [ensureAnyoneReaderPermission] throws it. */
    var ensureError: Exception? = null

    override suspend fun ensureAnyoneReaderPermission(fileId: String) {
        ensureError?.let { throw it }
        permissionEnsuredFileIds += fileId
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

private class RecordingUploader : DriveUploader {
    data class Call(
        val businessName: String,
        val target: DriveTarget,
        val fileName: String,
        val mimeType: String,
    )

    val calls = mutableListOf<Call>()

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
