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
import com.itsluminous.samaroh.core.model.FileItem
import com.itsluminous.samaroh.core.model.FilesPermissions
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.MemberStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.files.domain.FileNameError
import com.itsluminous.samaroh.feature.files.domain.FolderNameError
import com.itsluminous.samaroh.feature.files.domain.MoveError
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
    private lateinit var mirror: RecordingDriveMirror
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
        mirror = RecordingDriveMirror()
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
            driveMirror = mirror,
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

    // ---- share picker "New folder" (ADR-087) ----

    @Test
    fun `picker new folder creates under the picked parent and returns its id right away`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            // The picker validates against the SELECTED parent's siblings, not the current folder.
            assertThat(vm.validateFolderName("2026", parentId = "root")).isEqualTo(FolderNameError.DUPLICATE)
            assertThat(vm.validateFolderName("2026", parentId = null)).isNull()
            assertThat(vm.createFolder("2026", parentId = "root")).isNull()
            val id = vm.createFolder("Receipts", parentId = "root")
            assertThat(id).isNotNull()
            val created = repository.foldersFlow.value.single { it.name == "Receipts" }
            assertThat(created.id).isEqualTo(id)
            assertThat(created.parentId).isEqualTo("root")
            assertThat(created.createdBy).isEqualTo(Fixtures.USER_ID)
            // The tab's own current folder (top level) is untouched.
            assertThat(vm.uiState.first { it.folders.any { it.folder.name == "Contracts" } }.folderId).isNull()
        }

    @Test
    fun `share payload saves into a folder created from the picker`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            val a = File(context.cacheDir, "picker-a.pdf").apply { writeBytes(ByteArray(5)) }
            shareHolder.set(listOf(SharedFile(Uri.fromFile(a), "application/pdf", "picker-a.pdf", 5)))
            var newId: String? = null
            vm.events.test {
                newId = vm.createFolder("Tenders", parentId = null)
                assertThat(newId).isNotNull()
                vm.saveSharedFiles(newId)
                // Folder create event first (its save is awaited), then the share confirmation.
                assertThat(awaitItem()).isEqualTo(FilesEvent.FolderCreated("Tenders"))
                assertThat(awaitItem()).isEqualTo(FilesEvent.SharedSaved(1))
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(
                repository.foldersFlow.value
                    .single { it.id == newId }
                    .name,
            ).isEqualTo("Tenders")
            val staged = repository.filesFlow.value.single { it.file.folderId == newId }
            assertThat(staged.file.name).isEqualTo("picker-a.pdf")
            assertThat(shareHolder.peek()).isEmpty()
        }

    @Test
    fun `picker new folder respects the depth cap under the picked parent`() =
        runTest {
            // Chain of MAX_FOLDER_DEPTH folders: the deepest one cannot take a child.
            var parent: String? = null
            val chain =
                (1..com.itsluminous.samaroh.core.model.FileItem.MAX_FOLDER_DEPTH).map { level ->
                    folderFixture("d$level", name = "L$level", parentId = parent).also { parent = it.id }
                }
            repository.foldersFlow.value = chain
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            assertThat(vm.canCreateSubfolderIn(null)).isTrue()
            assertThat(vm.canCreateSubfolderIn(chain[chain.size - 2].id)).isTrue()
            assertThat(vm.canCreateSubfolderIn(chain.last().id)).isFalse()
            assertThat(vm.createFolder("Too deep", parentId = chain.last().id)).isNull()
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

    // ------------------------------------------------------------------ ADR-090: rename / move / open

    @Test
    fun `renaming a file updates the row through the repository, mirrors to Drive and reports`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            val row = repository.filesFlow.value.single { it.file.id == "f-root" }
            assertThat(vm.validateFileName("a/b")).isEqualTo(FileNameError.INVALID)
            vm.events.test {
                vm.renameFile(row, "  lease-2026.pdf ")
                assertThat(awaitItem()).isEqualTo(FilesEvent.FileRenamed)
                cancelAndIgnoreRemainingEvents()
            }
            val renamed =
                repository.filesFlow.value
                    .single { it.file.id == "f-root" }
                    .file
            assertThat(renamed.name).isEqualTo("lease-2026.pdf")
            assertThat(renamed.folderId).isEqualTo("root")
            assertThat(renamed.driveFileId).isEqualTo("drive-f-root")
            assertThat(mirror.calls).containsExactly("renameFile:drive-f-root:lease-2026.pdf")

            // Invalid or unchanged names are refused without touching anything.
            vm.renameFile(repository.filesFlow.value.single { it.file.id == "f-root" }, "lease-2026.pdf")
            vm.renameFile(repository.filesFlow.value.single { it.file.id == "f-root" }, "")
            assertThat(mirror.calls).hasSize(1)
        }

    @Test
    fun `renaming a staged (not yet uploaded) file skips the Drive mirror`() =
        runTest {
            seedTree()
            repository.filesFlow.value =
                repository.filesFlow.value + fileFixture("staged", name = "draft.txt", mimeType = "text/plain", driveFileId = null)
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            vm.renameFile(repository.filesFlow.value.single { it.file.id == "staged" }, "final.txt")
            assertThat(
                repository.filesFlow.value
                    .single { it.file.id == "staged" }
                    .file.name,
            ).isEqualTo("final.txt")
            assertThat(mirror.calls).isEmpty()
        }

    @Test
    fun `moving a file changes its folder, mirrors the new path and names the destination`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            val row = repository.filesFlow.value.single { it.file.id == "f-top" }
            val target = MoveTarget.ForFile(row)
            assertThat(target.currentParentId).isNull()
            assertThat(vm.validateMove(target, null)).isEqualTo(MoveError.SAME_LOCATION)
            assertThat(vm.validateMove(target, "child")).isNull()
            vm.events.test {
                vm.move(target, "child")
                assertThat(awaitItem()).isEqualTo(FilesEvent.Moved("2026"))
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(
                repository.filesFlow.value
                    .single { it.file.id == "f-top" }
                    .file.folderId,
            ).isEqualTo("child")
            assertThat(mirror.calls).containsExactly("moveFile:drive-f-top:fixture-business:Contracts/2026")
        }

    @Test
    fun `moving a folder re-parents it, mirrors old path to new parent path, and refuses cycles`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            val root = repository.foldersFlow.value.single { it.id == "root" }
            val child = repository.foldersFlow.value.single { it.id == "child" }
            // Cycle: Contracts into its own child — validation says so and move() is a no-op.
            assertThat(vm.validateMove(MoveTarget.ForFolder(root), "child")).isEqualTo(MoveError.INTO_SELF)
            vm.move(MoveTarget.ForFolder(root), "child")
            assertThat(
                repository.foldersFlow.value
                    .single { it.id == "root" }
                    .parentId,
            ).isNull()
            assertThat(mirror.calls).isEmpty()

            // Legit: 2026 out of Contracts into Photos.
            assertThat(MoveTarget.ForFolder(child).currentParentId).isEqualTo("root")
            vm.events.test {
                vm.move(MoveTarget.ForFolder(child), "sib")
                assertThat(awaitItem()).isEqualTo(FilesEvent.Moved("Photos"))
                cancelAndIgnoreRemainingEvents()
            }
            val moved = repository.foldersFlow.value.single { it.id == "child" }
            assertThat(moved.parentId).isEqualTo("sib")
            assertThat(moved.updatedBy).isEqualTo(Fixtures.USER_ID)
            assertThat(mirror.calls).containsExactly("moveFolder:fixture-business:Contracts/2026:Photos")

            // To the top level: destination name is null (the screen renders the root label).
            vm.uiState.first { it.allFolders.single { f -> f.id == "child" }.parentId == "sib" }
            vm.events.test {
                vm.move(MoveTarget.ForFolder(repository.foldersFlow.value.single { it.id == "child" }), null)
                assertThat(awaitItem()).isEqualTo(FilesEvent.Moved(null))
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(mirror.calls.last()).isEqualTo("moveFolder:fixture-business:Photos/2026:")
        }

    @Test
    fun `renaming a folder mirrors the old path and the new name to Drive`() =
        runTest {
            seedTree()
            val vm = viewModel()
            vm.uiState.first { !it.loading }
            vm.events.test {
                vm.renameFolder(repository.foldersFlow.value.single { it.id == "child" }, "2027")
                assertThat(awaitItem()).isEqualTo(FilesEvent.FolderRenamed)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(mirror.calls).containsExactly("renameFolder:fixture-business:Contracts/2026:2027")
        }

    @Test
    fun `file rename-move gate is per row - delete holders any file, uploaders only their own`() =
        runTest {
            seedTree()
            repository.filesFlow.value =
                repository.filesFlow.value +
                fileFixture("f-mine", name = "mine.pdf", mimeType = "application/pdf", createdBy = "user-staff")

            fun gates(state: FilesUiState) = state.files.associate { it.file.name to it.canRenameOrMove }

            val staff = fakeFilesSession(userId = "user-staff", isOwner = false, permissions = MemberPermissions.staff())
            val staffState = viewModel(staff).uiState.first { !it.loading }
            assertThat(staffState.userId).isEqualTo("user-staff")
            assertThat(gates(staffState)).containsExactly("logo.png", false, "mine.pdf", true)

            val manager =
                fakeFilesSession(
                    userId = "user-staff",
                    isOwner = false,
                    permissions = MemberPermissions(files = FilesPermissions(view = true, delete = true)),
                )
            assertThat(gates(viewModel(manager).uiState.first { !it.loading })).containsExactly("logo.png", true, "mine.pdf", true)

            val viewer =
                fakeFilesSession(
                    userId = "user-staff",
                    isOwner = false,
                    permissions = MemberPermissions(files = FilesPermissions(view = true)),
                )
            assertThat(gates(viewModel(viewer).uiState.first { !it.loading }).values).containsExactly(false, false)

            // Owner passes everything.
            assertThat(gates(viewModel().uiState.first { !it.loading }).values).containsExactly(true, true)
        }

    @Test
    fun `open routes images to the in-app viewer and everything else to the system via local bytes - never a Drive url`() =
        runTest {
            seedTree()
            val vm = viewModel()
            val state = vm.uiState.first { !it.loading }
            vm.events.test {
                vm.openFile(state.files.single { it.file.name == "logo.png" })
                val image = awaitItem()
                assertThat(image).isInstanceOf(FilesEvent.OpenImage::class.java)
                assertThat((image as FilesEvent.OpenImage).file.exists()).isTrue()

                vm.openFolder("root")
                val pdf =
                    vm.uiState
                        .first { it.folderId == "root" }
                        .files
                        .single()
                vm.openFile(pdf)
                val opened = awaitItem()
                assertThat(opened).isInstanceOf(FilesEvent.OpenWithApp::class.java)
                assertThat((opened as FilesEvent.OpenWithApp).mimeType).isEqualTo("application/pdf")
                assertThat(opened.file.exists()).isTrue()

                // The explicit action still targets the Drive viewer URL.
                vm.openInDrive(pdf)
                assertThat(awaitItem()).isEqualTo(FilesEvent.OpenUrl(FileItem.viewUrl("drive-f-root")))
                cancelAndIgnoreRemainingEvents()
            }
        }
}
