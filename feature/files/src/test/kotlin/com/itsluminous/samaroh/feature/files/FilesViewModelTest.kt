package com.itsluminous.samaroh.feature.files

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.share.ShareIntakeHolder
import com.itsluminous.samaroh.core.data.share.SharedFile
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.drive.DriveFileFetcher
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.FilesPermissions
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.MemberStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.files.domain.FolderNameError
import com.itsluminous.samaroh.feature.files.open.FileContentResolver
import com.itsluminous.samaroh.feature.files.upload.FileUploadIntake
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Files tab ViewModel (ADR-085): per-folder listing, global search with path, permission
 * gates, folder create/rename/delete cascade, upload staging + link prompt, access editor.
 */
@RunWith(RobolectricTestRunner::class)
class FilesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now: Instant = Instant.parse("2026-09-25T06:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var context: Context
    private lateinit var repository: FakeFilesRepository
    private lateinit var members: FakeMemberRepository
    private lateinit var scheduler: RecordingSyncScheduler
    private lateinit var deleter: RecordingDriveDeleter
    private lateinit var linker: FakeGoogleAccountLinker
    private lateinit var shareHolder: ShareIntakeHolder
    private var dataStoreIndex = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = FakeFilesRepository(now)
        members = FakeMemberRepository()
        scheduler = RecordingSyncScheduler()
        deleter = RecordingDriveDeleter()
        linker = FakeGoogleAccountLinker(GoogleLinkState.Linked("o@example.com", emptyList()))
        shareHolder = ShareIntakeHolder()
    }

    private fun viewModel(session: FilesSession = fakeFilesSession()): FilesViewModel {
        val prefs =
            FilesViewPreferences(
                PreferenceDataStoreFactory.create { File(context.cacheDir, "files-prefs-${dataStoreIndex++}.preferences_pb") },
            )
        return FilesViewModel(
            savedStateHandle = SavedStateHandle(),
            session = session,
            repository = repository,
            memberRepository = members,
            uploadIntake = FileUploadIntake(context, repository, scheduler, clock),
            contentResolver = FileContentResolver(context, DriveFileFetcher(FakeDriveService(), linker), repository),
            driveDeleter = deleter,
            googleAccountLinker = linker,
            viewPreferences = prefs,
            shareIntakeHolder = shareHolder,
            clock = clock,
        )
    }

    private fun seedTree() {
        repository.foldersFlow.value =
            listOf(
                folderFixture("root", name = "Contracts"),
                folderFixture("child", name = "2026", parentId = "root", restricted = true),
                folderFixture("sib", name = "Photos"),
            )
        repository.filesFlow.value =
            listOf(
                fileFixture("f-top", name = "logo.png"),
                fileFixture("f-root", folderId = "root", name = "lease.pdf", mimeType = "application/pdf"),
                fileFixture("f-child", folderId = "child", name = "scan.jpg"),
            )
        members.members.value =
            listOf(
                BusinessMember(
                    id = "m-owner",
                    businessId = Fixtures.BUSINESS_ID,
                    invitedEmail = "o@example.com",
                    userId = Fixtures.USER_ID,
                    displayName = "Owner Ji",
                    isOwner = true,
                    status = MemberStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                ),
                BusinessMember(
                    id = "m-staff",
                    businessId = Fixtures.BUSINESS_ID,
                    invitedEmail = "s@example.com",
                    userId = "user-staff",
                    displayName = "Staff",
                    status = MemberStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
    }

    @Test
    fun `top level lists folders A-Z then files newest first with added-by names`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.test {
                val state = awaitItem().let { if (it.loading) awaitItem() else it }
                assertThat(state.folders.map { it.folder.name }).containsExactly("Contracts", "Photos").inOrder()
                assertThat(state.folders.first().itemCount).isEqualTo(2)
                assertThat(state.files.map { it.file.name }).containsExactly("logo.png")
                assertThat(state.files.single().addedBy).isEqualTo("Owner Ji")
                assertThat(state.breadcrumbs).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `opening a folder scopes the listing and builds breadcrumbs, up navigates back`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            vm.openFolder("child")
            val inChild = vm.uiState.first { it.folderId == "child" }
            assertThat(inChild.breadcrumbs.map { it.name }).containsExactly("Contracts", "2026").inOrder()
            assertThat(inChild.files.map { it.file.name }).containsExactly("scan.jpg")
            assertThat(inChild.folders).isEmpty()
            assertThat(vm.navigateUp()).isTrue()
            assertThat(
                vm.uiState
                    .first { it.folderId == "root" }
                    .files
                    .map { it.file.name },
            ).containsExactly("lease.pdf")
            assertThat(vm.navigateUp()).isTrue()
            assertThat(vm.uiState.first { it.folderId == null }.folderId).isNull()
            assertThat(vm.navigateUp()).isFalse()
        }

    @Test
    fun `search is global across every accessible folder and carries the parent path`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            vm.openFolder("sib")
            vm.onQueryChange("SCAN")
            val hits = vm.uiState.first { it.searchHits != null }.searchHits!!
            assertThat(hits).hasSize(1)
            assertThat(
                hits
                    .single()
                    .file!!
                    .file.id,
            ).isEqualTo("f-child")
            assertThat(hits.single().parentFolderId).isEqualTo("child")
            vm.onQueryChange("20")
            assertThat(
                vm.uiState
                    .first { it.searchHits?.size == 1 }
                    .searchHits!!
                    .single()
                    .folder!!
                    .folder.id,
            ).isEqualTo("child")
            vm.onQueryChange("")
            assertThat(vm.uiState.first { it.searchHits == null }.folderId).isEqualTo("sib")
        }

    @Test
    fun `permission gates hide upload, folder management and delete for a viewer`() =
        runTest {
            seedTree()
            val viewer =
                fakeFilesSession(
                    userId = "user-staff",
                    isOwner = false,
                    permissions = MemberPermissions(files = FilesPermissions(view = true)),
                )
            val state = viewModel(viewer).uiState.first { !it.loading }
            assertThat(state.canUpload).isFalse()
            assertThat(state.canManageFolders).isFalse()
            assertThat(state.canDelete).isFalse()
            assertThat(state.isOwner).isFalse()

            // Staff preset: upload + inherited manage_folders, no delete.
            val staff = fakeFilesSession(userId = "user-staff", isOwner = false, permissions = MemberPermissions.staff())
            val staffState = viewModel(staff).uiState.first { !it.loading }
            assertThat(staffState.canUpload).isTrue()
            assertThat(staffState.canManageFolders).isTrue()
            assertThat(staffState.canDelete).isFalse()

            // Explicit manage_folders=false never falls through to upload.
            val locked =
                fakeFilesSession(
                    userId = "user-staff",
                    isOwner = false,
                    permissions = MemberPermissions(files = FilesPermissions(view = true, upload = true, manageFolders = false)),
                )
            assertThat(viewModel(locked).uiState.first { !it.loading }.canManageFolders).isFalse()
        }

    @Test
    fun `creating a folder refuses live duplicates case-insensitively and saves otherwise`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            assertThat(vm.validateFolderName("contracts")).isEqualTo(FolderNameError.DUPLICATE)
            vm.createFolder("contracts")
            assertThat(repository.foldersFlow.value.count { it.name.equals("contracts", ignoreCase = true) }).isEqualTo(1)
            vm.createFolder("Invoices")
            val created = repository.foldersFlow.value.single { it.name == "Invoices" }
            assertThat(created.parentId).isNull()
            assertThat(created.createdBy).isEqualTo(Fixtures.USER_ID)
            assertThat(created.restricted).isFalse()
        }

    @Test
    fun `deleting a folder tombstones its subtree, best-effort deletes drive copies and returns to the parent`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            vm.openFolder("child")
            vm.uiState.first { it.folderId == "child" }
            val root = repository.foldersFlow.value.single { it.id == "root" }
            assertThat(vm.subtreeCount(root)).isEqualTo(3) // child, lease.pdf, scan.jpg
            vm.deleteFolder(root)
            val after = vm.uiState.first { it.folderId == null }
            assertThat(after.folders.map { it.folder.name }).containsExactly("Photos")
            assertThat(deleter.deleted).containsExactly("drive-f-root", "drive-f-child")
        }

    @Test
    fun `deleting a file tombstones it and deletes the drive copy best-effort`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            vm.deleteFile(repository.filesFlow.value.single { it.file.id == "f-top" })
            assertThat(vm.uiState.first { it.files.isEmpty() }.files).isEmpty()
            assertThat(deleter.deleted).containsExactly("drive-f-top")
        }

    @Test
    fun `upload tap prompts to connect Google when unlinked and opens the picker when linked`() =
        runTest {
            seedTree()
            linker.state.value = GoogleLinkState.NotLinked
            val vm = viewModel()
            vm.events.test {
                vm.onUploadTapped()
                assertThat(awaitItem()).isEqualTo(FilesEvent.PromptGoogleLink)
                linker.state.value = GoogleLinkState.Linked("o@example.com", emptyList())
                vm.onUploadTapped()
                assertThat(awaitItem()).isEqualTo(FilesEvent.OpenPicker)
                // Unconfigured Google (offline-only install): straight to the picker, stage locally.
                linker.state.value = GoogleLinkState.NotConfigured
                vm.onUploadTapped()
                assertThat(awaitItem()).isEqualTo(FilesEvent.OpenPicker)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `picked files are staged into the current folder with a null drive id and a sync nudge`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            vm.openFolder("root")
            val source = File(context.cacheDir, "picked.txt").apply { writeText("hello files") }
            vm.events.test {
                vm.onUploadPicked(listOf(Uri.fromFile(source)))
                val event = awaitItem() as FilesEvent.UploadStaged
                assertThat(event.count).isEqualTo(1)
                assertThat(event.pendingUnlinked).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
            val staged = repository.filesFlow.value.single { it.file.folderId == "root" && it.file.driveFileId == null }
            assertThat(staged.file.name).isEqualTo("picked.txt")
            assertThat(staged.file.sizeBytes).isEqualTo(11L)
            assertThat(File(staged.localCachePath!!).readText()).isEqualTo("hello files")
            assertThat(staged.file.createdBy).isEqualTo(Fixtures.USER_ID)
            assertThat(scheduler.immediateRequests).isEqualTo(1)
        }

    @Test
    fun `share payload saves into the picked folder and reports the count`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            val a = File(context.cacheDir, "a.pdf").apply { writeBytes(ByteArray(5)) }
            val b = File(context.cacheDir, "b.pdf").apply { writeBytes(ByteArray(6)) }
            shareHolder.set(
                listOf(
                    SharedFile(Uri.fromFile(a), "application/pdf", "a.pdf", 5),
                    SharedFile(Uri.fromFile(b), "application/pdf", "b.pdf", 6),
                ),
            )
            vm.events.test {
                vm.saveSharedFiles("sib")
                assertThat(awaitItem()).isEqualTo(FilesEvent.SharedSaved(2))
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(
                repository.filesFlow.value
                    .filter { it.file.folderId == "sib" }
                    .map { it.file.name },
            ).containsExactly("a.pdf", "b.pdf")
            assertThat(shareHolder.peek()).isEmpty()
        }

    @Test
    fun `oversized picks are skipped with a too-large event before any staging`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            shareHolder.set(listOf(SharedFile(Uri.parse("content://x/big.bin"), "application/octet-stream", "big.bin", 30L * 1024 * 1024)))
            vm.events.test {
                vm.saveSharedFiles(null)
                assertThat(awaitItem()).isEqualTo(FilesEvent.UploadTooLarge("big.bin"))
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(repository.filesFlow.value.none { it.file.name == "big.bin" }).isTrue()
            assertThat(scheduler.immediateRequests).isEqualTo(0)
        }

    @Test
    fun `access editor saves restricted flag and diffs allow-list rows as soft links`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            val photos = repository.foldersFlow.value.single { it.id == "sib" }
            vm.openAccessEditor(photos)
            val editor = vm.accessEditor.first { it != null }!!
            assertThat(editor.members.map { it.id }).containsExactly("m-staff") // owner row excluded
            assertThat(editor.restricted).isFalse()
            vm.setAccessRestricted(true)
            vm.toggleAccessMember("m-staff")
            vm.saveAccess()
            vm.accessEditor.first { it == null }
            assertThat(
                repository.foldersFlow.value
                    .single { it.id == "sib" }
                    .restricted,
            ).isTrue()
            val grant = repository.accessFlow.value.single()
            assertThat(grant.folderId).isEqualTo("sib")
            assertThat(grant.memberId).isEqualTo("m-staff")
            assertThat(grant.deletedAt).isNull()

            // Revoke: back to everyone → the same PK row gets deleted_at (soft link).
            vm.openAccessEditor(repository.foldersFlow.value.single { it.id == "sib" })
            vm.accessEditor.first { it != null }
            vm.setAccessRestricted(false)
            vm.saveAccess()
            vm.accessEditor.first { it == null }
            assertThat(
                repository.accessFlow.value
                    .single()
                    .deletedAt,
            ).isEqualTo(now)
            assertThat(
                repository.foldersFlow.value
                    .single { it.id == "sib" }
                    .restricted,
            ).isFalse()
        }

    @Test
    fun `a file under a tombstoned folder is hidden client-side`() =
        runTest {
            seedTree()
            repository.foldersFlow.value = repository.foldersFlow.value.map { if (it.id == "child") it.copy(deletedAt = now) else it }
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            vm.onQueryChange("scan")
            assertThat(vm.uiState.first { it.searchHits != null }.searchHits).isEmpty()
            vm.onQueryChange("")
            vm.openFolder("child")
            assertThat(vm.uiState.first { it.folderId == "child" }.folderMissing).isTrue()
        }
}
