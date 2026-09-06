package com.itsluminous.samaroh.feature.expenses.attachments

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.repository.AttachmentWithLocalState
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.model.ExpenseAttachment
import com.itsluminous.samaroh.feature.expenses.FakeDriveService
import com.itsluminous.samaroh.feature.expenses.FakeExpensesLedgerRepository
import com.itsluminous.samaroh.feature.expenses.FakeGoogleAccountLinker
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * The viewer delete cascade (ADR-053): metadata tombstone (repository → outbox), local
 * cache removal, and the BEST-EFFORT Drive `files.delete` — which must never fail the
 * delete itself.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentDeleterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var ledgerRepository: FakeExpensesLedgerRepository
    private lateinit var driveService: FakeDriveService
    private lateinit var linker: FakeGoogleAccountLinker
    private lateinit var deleter: AttachmentDeleter

    @Before
    fun setUp() {
        ledgerRepository = FakeExpensesLedgerRepository()
        driveService = FakeDriveService()
        linker = FakeGoogleAccountLinker(GoogleLinkState.Linked("test@example.com", emptyList()))
        deleter =
            AttachmentDeleter(
                ledgerRepository = ledgerRepository,
                driveService = driveService,
                googleAccountLinker = linker,
                ioDispatcher = UnconfinedTestDispatcher(),
            )
    }

    private fun attachment(
        driveFileId: String? = null,
        localCachePath: String? = null,
    ): AttachmentWithLocalState =
        AttachmentWithLocalState(
            attachment =
                ExpenseAttachment(
                    id = UUID.randomUUID().toString(),
                    expenseId = "expense-1",
                    businessId = "business-1",
                    driveFileId = driveFileId,
                    mimeType = "image/jpeg",
                    fileName = "bill.jpg",
                    createdAt = Instant.parse("2026-09-01T10:00:00Z"),
                ),
            localCachePath = localCachePath,
        )

    @Test
    fun `tombstones the row, removes the cached file and deletes the drive copy`() =
        runTest {
            val cached = tempFolder.newFile("bill.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val tapped = attachment(driveFileId = "drive-9", localCachePath = cached.absolutePath)
            ledgerRepository.attachments.value = listOf(tapped)

            deleter.delete(tapped)

            assertThat(ledgerRepository.deletedAttachmentIds).containsExactly(tapped.attachment.id)
            assertThat(cached.exists()).isFalse()
            assertThat(driveService.deletedFileIds).containsExactly("drive-9")
        }

    @Test
    fun `drive delete failure is non-fatal - tombstone and cache removal still land`() =
        runTest {
            driveService.deleteError = IOException("offline")
            val cached = tempFolder.newFile("bill2.jpg")
            val tapped = attachment(driveFileId = "drive-9", localCachePath = cached.absolutePath)

            deleter.delete(tapped) // must not throw

            assertThat(ledgerRepository.deletedAttachmentIds).containsExactly(tapped.attachment.id)
            assertThat(cached.exists()).isFalse()
        }

    @Test
    fun `not linked - drive copy is left alone, metadata tombstone still wins`() =
        runTest {
            linker.state.value = GoogleLinkState.NotLinked
            val tapped = attachment(driveFileId = "drive-9")

            deleter.delete(tapped)

            assertThat(ledgerRepository.deletedAttachmentIds).containsExactly(tapped.attachment.id)
            assertThat(driveService.deletedFileIds).isEmpty()
        }

    @Test
    fun `pending upload - no drive id, no drive call, no crash on missing cache file`() =
        runTest {
            val tapped = attachment(driveFileId = null, localCachePath = "/nonexistent/gone.jpg")

            deleter.delete(tapped)

            assertThat(ledgerRepository.deletedAttachmentIds).containsExactly(tapped.attachment.id)
            assertThat(driveService.deletedFileIds).isEmpty()
        }
}
