package com.itsluminous.samaroh.feature.expenses.attachments

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.drive.DriveFileFetcher
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import com.itsluminous.samaroh.core.model.ExpenseAttachment
import com.itsluminous.samaroh.feature.expenses.FakeDriveService
import com.itsluminous.samaroh.feature.expenses.FakeExpensesLedgerRepository
import com.itsluminous.samaroh.feature.expenses.FakeGoogleAccountLinker
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** ADR-052 source-resolution ladder + ADR-059 public-link member fallback. */
@RunWith(RobolectricTestRunner::class)
class AttachmentContentResolverTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var attachmentsDir: File
    private lateinit var driveService: FakeDriveService
    private lateinit var linker: FakeGoogleAccountLinker
    private lateinit var ledgerRepository: FakeExpensesLedgerRepository
    private lateinit var resolver: AttachmentContentResolver

    @Before
    fun setUp() {
        attachmentsDir = tempFolder.newFolder("expense_attachments")
        driveService = FakeDriveService()
        linker = FakeGoogleAccountLinker(GoogleLinkState.Linked("test@example.com", emptyList()))
        ledgerRepository = FakeExpensesLedgerRepository()
        resolver =
            AttachmentContentResolver(
                attachmentsDir = { attachmentsDir },
                driveFileFetcher = DriveFileFetcher(driveService, linker),
                ledgerRepository = ledgerRepository,
                ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(),
            )
    }

    private fun attachment(
        driveFileId: String? = null,
        localCachePath: String? = null,
        mimeType: String = "image/jpeg",
    ): AttachmentWithLocalState =
        AttachmentWithLocalState(
            attachment =
                ExpenseAttachment(
                    id = UUID.randomUUID().toString(),
                    expenseId = "expense-1",
                    businessId = "business-1",
                    driveFileId = driveFileId,
                    mimeType = mimeType,
                    fileName = "bill.jpg",
                    createdAt = Instant.parse("2026-09-01T10:00:00Z"),
                ),
            localCachePath = localCachePath,
        )

    @Test
    fun `path 1 - live local cache opens directly without touching drive`() =
        runTest {
            val cached = tempFolder.newFile("cached-bill.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            val result = resolver.resolve(attachment(driveFileId = "drive-1", localCachePath = cached.absolutePath))

            assertThat(result).isEqualTo(AttachmentOpenResult.Ready(cached, "image/jpeg"))
            assertThat(driveService.downloadedFileIds).isEmpty()
        }

    @Test
    fun `path 2 - drive copy downloads, caches and stamps room`() =
        runTest {
            driveService.downloadBytes = "downloaded-bill".toByteArray()
            val tapped = attachment(driveFileId = "drive-1")

            val result = resolver.resolve(tapped)

            val ready = result as AttachmentOpenResult.Ready
            assertThat(ready.mimeType).isEqualTo("image/jpeg")
            assertThat(ready.file.parentFile).isEqualTo(attachmentsDir)
            assertThat(ready.file.readBytes()).isEqualTo("downloaded-bill".toByteArray())
            assertThat(driveService.downloadedFileIds).containsExactly("drive-1")
            assertThat(ledgerRepository.cachePathUpdates)
                .containsExactly(tapped.attachment.id to ready.file.absolutePath)
        }

    @Test
    fun `path 2 - stale cache path (file deleted) falls through to the drive download`() =
        runTest {
            val result =
                resolver.resolve(
                    attachment(driveFileId = "drive-1", localCachePath = File(attachmentsDir, "gone.jpg").absolutePath),
                )

            assertThat(result).isInstanceOf(AttachmentOpenResult.Ready::class.java)
            assertThat(driveService.downloadedFileIds).containsExactly("drive-1")
        }

    @Test
    fun `path 3 - not linked falls back to the public link download, no prompt`() =
        runTest {
            linker.state.value = GoogleLinkState.NotLinked
            driveService.publicDownloadBytes = "public-bill".toByteArray()
            val tapped = attachment(driveFileId = "drive-1")

            val result = resolver.resolve(tapped)

            val ready = result as AttachmentOpenResult.Ready
            assertThat(ready.file.readBytes()).isEqualTo("public-bill".toByteArray())
            assertThat(driveService.downloadedFileIds).isEmpty()
            assertThat(driveService.publicDownloadedFileIds).containsExactly("drive-1")
            assertThat(ledgerRepository.cachePathUpdates)
                .containsExactly(tapped.attachment.id to ready.file.absolutePath)
        }

    @Test
    fun `path 3 - linked but own token cannot read the file (another member's bill) falls back to public link`() =
        runTest {
            driveService.downloadError = GoogleApiException(404, "not found")
            driveService.publicDownloadBytes = "member-bill".toByteArray()

            val result = resolver.resolve(attachment(driveFileId = "drive-1"))

            val ready = result as AttachmentOpenResult.Ready
            assertThat(ready.file.readBytes()).isEqualTo("member-bill".toByteArray())
            assertThat(driveService.publicDownloadedFileIds).containsExactly("drive-1")
        }

    @Test
    fun `path 4 - not linked and the file is not link-shared needs google link`() =
        runTest {
            linker.state.value = GoogleLinkState.NotLinked
            driveService.publicDownloadError = GoogleApiException(404, "file is not link-shared")

            val result = resolver.resolve(attachment(driveFileId = "drive-1"))

            assertThat(result).isEqualTo(AttachmentOpenResult.NeedsGoogleLink)
            assertThat(driveService.downloadedFileIds).isEmpty()
        }

    @Test
    fun `not linked and offline reports failed, not the link prompt`() =
        runTest {
            linker.state.value = GoogleLinkState.NotLinked
            driveService.publicDownloadError = IOException("offline")

            val result = resolver.resolve(attachment(driveFileId = "drive-1"))

            assertThat(result).isEqualTo(AttachmentOpenResult.DownloadFailed)
        }

    @Test
    fun `linked with both ladder rungs failing reports failed`() =
        runTest {
            driveService.downloadError = IOException("offline")
            driveService.publicDownloadError = IOException("offline")

            val result = resolver.resolve(attachment(driveFileId = "drive-1"))

            assertThat(result).isEqualTo(AttachmentOpenResult.DownloadFailed)
            assertThat(attachmentsDir.listFiles()).isEmpty()
        }

    @Test
    fun `path 4 - neither cache nor drive copy is not available on this device`() =
        runTest {
            val result = resolver.resolve(attachment())

            assertThat(result).isEqualTo(AttachmentOpenResult.NotAvailable)
            assertThat(driveService.downloadedFileIds).isEmpty()
        }

    @Test
    fun `download failure reports failed, cleans the partial file and skips the room stamp`() =
        runTest {
            driveService.downloadError = IOException("offline")
            driveService.publicDownloadError = IOException("offline")
            val tapped = attachment(driveFileId = "drive-1")

            val result = resolver.resolve(tapped)

            assertThat(result).isEqualTo(AttachmentOpenResult.DownloadFailed)
            assertThat(ledgerRepository.cachePathUpdates).isEmpty()
            assertThat(attachmentsDir.listFiles()).isEmpty()
        }
}
