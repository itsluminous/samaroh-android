package com.itsluminous.samaroh.core.google.drive

import android.content.Context
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * The shared own-token → public-link download ladder (ADR-052/059/063) extracted from
 * the expense-attachment and item-photo fetchers: rung order, the empty-file guard, and
 * the failure payload (last error + linked flag) callers build their prompt policy on.
 */
@RunWith(RobolectricTestRunner::class)
class DriveFileFetcherTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val driveService = LadderDriveService()
    private val linker = StubLinker(GoogleLinkState.Linked("test@example.com", emptyList()))
    private val fetcher = DriveFileFetcher(driveService, linker)

    private fun target(): File = File(tempFolder.root, "drive-file.bin")

    @Test
    fun `linked own-token download succeeds on the first rung`() =
        runTest {
            driveService.downloadBytes = "own".toByteArray()
            val target = target()

            val result = fetcher.fetchInto("drive-1", target)

            assertThat(result).isEqualTo(DriveFetchResult.Success)
            assertThat(target.readBytes()).isEqualTo("own".toByteArray())
            assertThat(driveService.publicDownloads).isEmpty()
        }

    @Test
    fun `own-token failure falls through to the public rung`() =
        runTest {
            driveService.downloadError = GoogleApiException(404, "not yours")
            driveService.publicDownloadBytes = "public".toByteArray()
            val target = target()

            val result = fetcher.fetchInto("drive-1", target)

            assertThat(result).isEqualTo(DriveFetchResult.Success)
            assertThat(target.readBytes()).isEqualTo("public".toByteArray())
        }

    @Test
    fun `not linked skips the own-token rung entirely`() =
        runTest {
            linker.state.value = GoogleLinkState.NotLinked
            driveService.publicDownloadBytes = "public".toByteArray()

            val result = fetcher.fetchInto("drive-1", target())

            assertThat(result).isEqualTo(DriveFetchResult.Success)
            assertThat(driveService.downloads).isEmpty()
        }

    @Test
    fun `empty own-token result counts as failure and falls through`() =
        runTest {
            driveService.downloadBytes = ByteArray(0)
            driveService.publicDownloadBytes = "public".toByteArray()

            val result = fetcher.fetchInto("drive-1", target())

            assertThat(result).isEqualTo(DriveFetchResult.Success)
            assertThat(driveService.publicDownloads).containsExactly("drive-1")
        }

    @Test
    fun `both rungs failing reports the LAST error and the linked flag, deletes the target`() =
        runTest {
            driveService.downloadError = IOException("offline")
            val publicError = GoogleApiException(404, "not shared")
            driveService.publicDownloadError = publicError
            val target = target()

            val result = fetcher.fetchInto("drive-1", target)

            assertThat(result).isEqualTo(DriveFetchResult.Failure(publicError, linked = true))
            assertThat(target.exists()).isFalse()
        }

    @Test
    fun `not linked failure carries linked=false for the caller's prompt policy`() =
        runTest {
            linker.state.value = GoogleLinkState.NotLinked
            val error = GoogleApiException(404, "not shared")
            driveService.publicDownloadError = error

            val result = fetcher.fetchInto("drive-1", target())

            assertThat(result).isEqualTo(DriveFetchResult.Failure(error, linked = false))
        }

    private class StubLinker(
        initial: GoogleLinkState,
    ) : GoogleAccountLinker {
        val state = MutableStateFlow(initial)
        override val linkState: Flow<GoogleLinkState> = state

        override suspend fun link(activityContext: Context): Result<GoogleLinkState.Linked> = throw UnsupportedOperationException()

        override suspend fun completeLink(resultIntent: Intent?): Result<GoogleLinkState.Linked> = throw UnsupportedOperationException()

        override suspend fun unlink() = throw UnsupportedOperationException()
    }

    private class LadderDriveService : DriveService {
        var downloadBytes: ByteArray = "drive-bytes".toByteArray()
        var downloadError: Exception? = null
        var publicDownloadBytes: ByteArray = "public-bytes".toByteArray()
        var publicDownloadError: Exception? = null
        val downloads = mutableListOf<String>()
        val publicDownloads = mutableListOf<String>()

        override suspend fun downloadFile(
            fileId: String,
            target: File,
        ) {
            downloadError?.let { throw it }
            downloads += fileId
            target.writeBytes(downloadBytes)
        }

        override suspend fun downloadPublicFile(
            fileId: String,
            target: File,
        ) {
            publicDownloadError?.let { throw it }
            publicDownloads += fileId
            target.writeBytes(publicDownloadBytes)
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

        override suspend fun ensureAnyoneReaderPermission(fileId: String) = throw UnsupportedOperationException()

        override suspend fun deleteFile(fileId: String) = throw UnsupportedOperationException()
    }
}
