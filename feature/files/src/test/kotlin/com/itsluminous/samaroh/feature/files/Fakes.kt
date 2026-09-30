package com.itsluminous.samaroh.feature.files

import android.content.Context
import android.content.Intent
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.data.repository.FileWithLocalState
import com.itsluminous.samaroh.core.data.repository.FilesRepository
import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.data.sync.FilesDriveDeleter
import com.itsluminous.samaroh.core.data.sync.FilesDriveMirror
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import com.itsluminous.samaroh.core.google.drive.DriveFileRef
import com.itsluminous.samaroh.core.google.drive.DriveService
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.FileItem
import com.itsluminous.samaroh.core.model.Folder
import com.itsluminous.samaroh.core.model.FolderAccess
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.testing.Fixtures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.Instant

// In-memory fakes for the Files ViewModel/logic tests — no Room, no network.

class FakeFilesRepository(
    private val now: Instant = Fixtures.NOW,
) : FilesRepository {
    val foldersFlow = MutableStateFlow<List<Folder>>(emptyList())
    val filesFlow = MutableStateFlow<List<FileWithLocalState>>(emptyList())
    val accessFlow = MutableStateFlow<List<FolderAccess>>(emptyList())
    val stagedPaths = mutableMapOf<String, String>()

    override fun folders(businessId: String): Flow<List<Folder>> =
        foldersFlow.map { list -> list.filter { it.businessId == businessId && it.deletedAt == null }.sortedBy { it.name.lowercase() } }

    override fun files(businessId: String): Flow<List<FileWithLocalState>> =
        filesFlow.map { list ->
            list.filter { it.file.businessId == businessId && it.file.deletedAt == null }.sortedByDescending { it.file.createdAt }
        }

    override fun folderAccess(businessId: String): Flow<List<FolderAccess>> =
        accessFlow.map { list -> list.filter { it.businessId == businessId && it.deletedAt == null } }

    override suspend fun folder(id: String): Folder? = foldersFlow.value.firstOrNull { it.id == id }

    override suspend fun file(id: String): FileWithLocalState? = filesFlow.value.firstOrNull { it.file.id == id }

    override suspend fun liveSiblingNamed(
        businessId: String,
        parentId: String?,
        name: String,
    ): Folder? =
        foldersFlow.value.firstOrNull {
            it.businessId == businessId && it.deletedAt == null && it.parentId == parentId && it.name.equals(name.trim(), ignoreCase = true)
        }

    override suspend fun saveFolder(folder: Folder) {
        foldersFlow.value = foldersFlow.value.filterNot { it.id == folder.id } + folder
    }

    override suspend fun stageFile(
        file: FileItem,
        localPath: String,
    ) {
        stagedPaths[file.id] = localPath
        filesFlow.value = filesFlow.value.filterNot { it.file.id == file.id } + FileWithLocalState(file, localPath, false)
    }

    override suspend fun updateFile(file: FileItem) {
        filesFlow.value = filesFlow.value.map { if (it.file.id == file.id) it.copy(file = file) else it }
    }

    override suspend fun updateLocalCachePath(
        fileId: String,
        path: String?,
    ) {
        filesFlow.value = filesFlow.value.map { if (it.file.id == fileId) it.copy(localCachePath = path) else it }
    }

    override suspend fun deleteFile(id: String): FileWithLocalState? {
        val existing = filesFlow.value.firstOrNull { it.file.id == id } ?: return null
        val gone = existing.copy(file = existing.file.copy(deletedAt = now, updatedAt = now))
        filesFlow.value = filesFlow.value.map { if (it.file.id == id) gone else it }
        return gone
    }

    override suspend fun deleteFolderTree(folderId: String): List<FileWithLocalState> {
        val live = foldersFlow.value.filter { it.deletedAt == null }
        val ids = mutableSetOf(folderId)
        var frontier = setOf(folderId)
        while (frontier.isNotEmpty()) {
            frontier = live.filter { it.parentId in frontier }.map { it.id }.toSet() - ids
            ids += frontier
        }
        val removed = filesFlow.value.filter { it.file.folderId in ids && it.file.deletedAt == null }
        removed.forEach { deleteFile(it.file.id) }
        foldersFlow.value = foldersFlow.value.map { if (it.id in ids) it.copy(deletedAt = now, updatedAt = now) else it }
        return removed.map { it.copy(file = it.file.copy(deletedAt = now)) }
    }

    override suspend fun saveFolderAccess(access: FolderAccess) {
        accessFlow.value = accessFlow.value.filterNot { it.folderId == access.folderId && it.memberId == access.memberId } + access
    }

    override suspend fun folderAccessRows(folderId: String): List<FolderAccess> = accessFlow.value.filter { it.folderId == folderId }
}

class FakeMemberRepository : MemberRepository {
    val members = MutableStateFlow<List<BusinessMember>>(emptyList())

    override fun membersForBusiness(businessId: String): Flow<List<BusinessMember>> =
        members.map { list ->
            list.filter {
                it.businessId ==
                    businessId
            }
        }

    override suspend fun memberForUser(
        businessId: String,
        userId: String,
    ): BusinessMember? = members.value.firstOrNull { it.businessId == businessId && it.userId == userId }

    override suspend fun saveMember(member: BusinessMember) {
        members.value = members.value.filterNot { it.id == member.id } + member
    }
}

class RecordingSyncScheduler : SyncScheduler {
    var immediateRequests = 0

    override fun requestImmediateSync() {
        immediateRequests++
    }

    override fun ensurePeriodicSync() = Unit
}

class RecordingDriveDeleter : FilesDriveDeleter {
    val deleted = mutableListOf<String>()

    override suspend fun deleteBestEffort(driveFileId: String) {
        deleted += driveFileId
    }
}

/** Records every best-effort Drive rename/move mirror call (ADR-090). */
class RecordingDriveMirror : FilesDriveMirror {
    val calls = mutableListOf<String>()

    override suspend fun renameFile(
        driveFileId: String,
        newName: String,
    ) {
        calls += "renameFile:$driveFileId:$newName"
    }

    override suspend fun moveFile(
        driveFileId: String,
        businessName: String,
        newFolderPath: List<String>,
    ) {
        calls += "moveFile:$driveFileId:$businessName:${newFolderPath.joinToString("/")}"
    }

    override suspend fun renameFolder(
        businessName: String,
        folderPath: List<String>,
        newName: String,
    ) {
        calls += "renameFolder:$businessName:${folderPath.joinToString("/")}:$newName"
    }

    override suspend fun moveFolder(
        businessName: String,
        folderPath: List<String>,
        newParentPath: List<String>,
    ) {
        calls += "moveFolder:$businessName:${folderPath.joinToString("/")}:${newParentPath.joinToString("/")}"
    }
}

class FakeGoogleAccountLinker(
    initialState: GoogleLinkState = GoogleLinkState.NotLinked,
) : GoogleAccountLinker {
    val state = MutableStateFlow(initialState)
    var linkCalls = 0
    override val linkState: Flow<GoogleLinkState> = state

    override suspend fun link(activityContext: Context): Result<GoogleLinkState.Linked> {
        linkCalls++
        val linked = GoogleLinkState.Linked("test@example.com", emptyList())
        state.value = linked
        return Result.success(linked)
    }

    override suspend fun completeLink(resultIntent: Intent?): Result<GoogleLinkState.Linked> {
        val linked = GoogleLinkState.Linked("test@example.com", emptyList())
        state.value = linked
        return Result.success(linked)
    }

    override suspend fun unlink() {
        state.value = GoogleLinkState.NotLinked
    }
}

/** Drive service fake: downloads write [payload]; nothing else is exercised by the Files tests. */
class FakeDriveService(
    private val payload: ByteArray = byteArrayOf(1, 2, 3),
) : DriveService {
    val deleted = mutableListOf<String>()

    override suspend fun findFolder(
        name: String,
        parentId: String?,
    ): String? = null

    override suspend fun createFolder(
        name: String,
        parentId: String?,
    ): String = "folder"

    override suspend fun uploadFile(
        name: String,
        mimeType: String,
        parentId: String,
        sourceFile: File,
    ): DriveFileRef = DriveFileRef("drive-id", name)

    override suspend fun downloadFile(
        fileId: String,
        target: File,
    ) = target.writeBytes(payload)

    override suspend fun downloadPublicFile(
        fileId: String,
        target: File,
    ) = target.writeBytes(payload)

    override suspend fun ensureAnyoneReaderPermission(fileId: String) = Unit

    override suspend fun deleteFile(fileId: String) {
        deleted += fileId
    }

    override suspend fun fileParents(fileId: String): List<String> = emptyList()

    override suspend fun updateFile(
        fileId: String,
        name: String?,
        addParentId: String?,
        removeParentIds: List<String>,
    ) = Unit
}

fun fakeFilesSession(
    business: Business? = Fixtures.business(),
    userId: String? = null,
    isOwner: Boolean = true,
    permissions: MemberPermissions = MemberPermissions(),
): FilesSession =
    FilesSession(
        activeBusinessProvider =
            object : ActiveBusinessProvider {
                override val activeBusiness = MutableStateFlow(business)
            },
        currentUserProvider =
            object : CurrentUserProvider {
                override val currentUserId = MutableStateFlow(userId)
            },
        permissionGuard =
            object : PermissionGuard {
                override fun permissions(businessId: String) = MutableStateFlow(permissions)

                override fun isOwner(businessId: String) = MutableStateFlow(isOwner)
            },
    )

fun folderFixture(
    id: String,
    name: String = "Folder $id",
    parentId: String? = null,
    restricted: Boolean = false,
    businessId: String = Fixtures.BUSINESS_ID,
): Folder = Folder(id, businessId, parentId, name, restricted, Fixtures.USER_ID, null, Fixtures.NOW, Fixtures.NOW, null)

fun fileFixture(
    id: String,
    folderId: String? = null,
    name: String = "$id.jpg",
    mimeType: String = "image/jpeg",
    driveFileId: String? = "drive-$id",
    createdAt: Instant = Fixtures.NOW,
    localCachePath: String? = null,
    businessId: String = Fixtures.BUSINESS_ID,
    createdBy: String = Fixtures.USER_ID,
): FileWithLocalState =
    FileWithLocalState(
        FileItem(id, businessId, folderId, name, mimeType, 2048, driveFileId, createdBy, createdAt, createdAt, null),
        localCachePath,
        drivePermissionEnsured = false,
    )
