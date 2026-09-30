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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ADR-090 best-effort Drive mirror: linked-only, never throws (403/404 and network errors
 * swallowed), find-only source folders, find-or-create destinations, no-op re-parents.
 */
@RunWith(RobolectricTestRunner::class)
class DriveFilesMirrorTest {
    private val linker = StubLinker(GoogleLinkState.Linked("o@example.com", emptyList()))
    private val drive = RecordingDriveService()
    private val resolver = ScriptedResolver()
    private val mirror = DriveFilesMirror(drive, resolver, linker)

    @Test
    fun `unlinked account - every mirror call is a silent no-op`() =
        runTest {
            linker.state.value = GoogleLinkState.NotLinked
            mirror.renameFile("d1", "x")
            mirror.moveFile("d1", "Biz", listOf("A"))
            mirror.renameFolder("Biz", listOf("A"), "B")
            mirror.moveFolder("Biz", listOf("A"), emptyList())
            assertThat(drive.updates).isEmpty()
            assertThat(resolver.calls).isEmpty()
        }

    @Test
    fun `rename file patches the Drive name`() =
        runTest {
            mirror.renameFile("d1", "lease.pdf")
            assertThat(drive.updates).containsExactly("d1|name=lease.pdf|add=null|remove=")
        }

    @Test
    fun `move file resolves (find-or-create) the destination chain and swaps parents`() =
        runTest {
            resolver.ids["files/Contracts/2026|create"] = "dest"
            drive.parents["d1"] = listOf("old-parent")
            mirror.moveFile("d1", "Biz", listOf("Contracts", "2026"))
            assertThat(resolver.calls).containsExactly("Biz|files/Contracts/2026|create")
            assertThat(drive.updates).containsExactly("d1|name=null|add=dest|remove=old-parent")
        }

    @Test
    fun `move file already under the destination is a no-op`() =
        runTest {
            resolver.ids["files|create"] = "dest"
            drive.parents["d1"] = listOf("dest")
            mirror.moveFile("d1", "Biz", emptyList())
            assertThat(drive.updates).isEmpty()
        }

    @Test
    fun `rename folder is find-only - a missing mirror folder does nothing`() =
        runTest {
            mirror.renameFolder("Biz", listOf("Ghost"), "New")
            assertThat(resolver.calls).containsExactly("Biz|files/Ghost|find")
            assertThat(drive.updates).isEmpty()

            resolver.ids["files/Contracts|find"] = "folder-c"
            mirror.renameFolder("Biz", listOf("Contracts"), "Agreements")
            assertThat(drive.updates).containsExactly("folder-c|name=Agreements|add=null|remove=")
        }

    @Test
    fun `move folder finds the source, creates the destination chain and re-parents`() =
        runTest {
            resolver.ids["files/Contracts/2026|find"] = "folder-2026"
            resolver.ids["files/Photos|create"] = "folder-photos"
            drive.parents["folder-2026"] = listOf("folder-c")
            mirror.moveFolder("Biz", listOf("Contracts", "2026"), listOf("Photos"))
            assertThat(drive.updates).containsExactly("folder-2026|name=null|add=folder-photos|remove=folder-c")
        }

    @Test
    fun `403 and 404 from Drive are swallowed, as are other failures`() =
        runTest {
            drive.error = GoogleApiException(403, "not yours")
            mirror.renameFile("d1", "x")
            drive.error = GoogleApiException(404, "gone")
            mirror.renameFile("d1", "x")
            drive.error = IllegalStateException("network")
            mirror.renameFile("d1", "x")
            resolver.error = DriveNotAvailableException("no token")
            mirror.moveFile("d1", "Biz", emptyList())
            // Reaching here without an exception is the assertion; the calls were attempted.
            assertThat(drive.updateAttempts).isEqualTo(3)
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

    private class ScriptedResolver : DriveFolderResolver {
        val ids = mutableMapOf<String, String>()
        val calls = mutableListOf<String>()
        var error: Throwable? = null

        override suspend fun resolveFolderId(
            businessName: String,
            target: DriveTarget,
            create: Boolean,
        ): String? {
            error?.let { throw it }
            val path = DriveLayout.folderPathBelowRoot(businessName, target).drop(1).joinToString("/")
            val mode = if (create) "create" else "find"
            calls += "$businessName|$path|$mode"
            return ids["$path|$mode"]
        }
    }

    private class RecordingDriveService : DriveService {
        val updates = mutableListOf<String>()
        val parents = mutableMapOf<String, List<String>>()
        var error: Throwable? = null
        var updateAttempts = 0

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
        ) = throw UnsupportedOperationException()

        override suspend fun downloadPublicFile(
            fileId: String,
            target: File,
        ) = throw UnsupportedOperationException()

        override suspend fun ensureAnyoneReaderPermission(fileId: String) = throw UnsupportedOperationException()

        override suspend fun deleteFile(fileId: String) = throw UnsupportedOperationException()

        override suspend fun fileParents(fileId: String): List<String> = parents[fileId].orEmpty()

        override suspend fun updateFile(
            fileId: String,
            name: String?,
            addParentId: String?,
            removeParentIds: List<String>,
        ) {
            updateAttempts++
            error?.let { throw it }
            updates += "$fileId|name=$name|add=$addParentId|remove=${removeParentIds.joinToString(",")}"
        }
    }
}
