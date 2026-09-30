package com.itsluminous.samaroh.feature.files.domain

import com.itsluminous.samaroh.core.data.repository.FileWithLocalState
import com.itsluminous.samaroh.core.model.FileItem
import com.itsluminous.samaroh.core.model.Folder

/** Folder-name validation outcome (design D12 — mirrors the server CHECK + unique index). */
enum class FolderNameError {
    REQUIRED,
    INVALID,
    DUPLICATE,
}

/** File-name validation outcome (design D12 — mirrors the server CHECK; names may repeat). */
enum class FileNameError {
    REQUIRED,
    INVALID,
}

/** Why a move destination is refused (ADR-090, design D14 revised). */
enum class MoveError {
    /** Destination is where the item already lives. */
    SAME_LOCATION,

    /** A folder into itself or one of its own descendants (cycle guard). */
    INTO_SELF,

    /** Destination depth + the moved folder's subtree height would exceed [FileItem.MAX_FOLDER_DEPTH]. */
    TOO_DEEP,

    /** The destination already has a LIVE folder of that name (case-insensitive). */
    DUPLICATE,
}

/** One visible row of the LAZY folder picker (ADR-090): roots first, children only under expanded rows. */
data class PickerRow(
    val folder: Folder,
    /** 1 = top-level folder (the root label itself is rendered by the caller at depth 0). */
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
)

/** Pure Files-tree helpers over the per-business index (ADR-085) — unit-tested, no I/O. */
object FilesTree {
    /** Server CHECK: 1–120 chars after trim, no `/`. */
    const val MAX_FOLDER_NAME = 120

    /** Server CHECK on `files.name`: 1–255 chars after trim, no `/` (design D12). */
    const val MAX_FILE_NAME = 255

    /** Validates a candidate file name — no sibling uniqueness (file names may repeat, D12). */
    fun validateFileName(raw: String): FileNameError? {
        val name = raw.trim()
        if (name.isEmpty()) return FileNameError.REQUIRED
        if (name.length > MAX_FILE_NAME || name.contains('/')) return FileNameError.INVALID
        return null
    }

    /** Whether [candidateId] lies strictly below [ancestorId] (walks up the live chain). */
    fun isDescendant(
        candidateId: String,
        ancestorId: String,
        folders: List<Folder>,
    ): Boolean {
        val byId = folders.associateBy { it.id }
        var cursor = byId[candidateId]?.parentId
        var guard = 0
        while (cursor != null && guard++ < FileItem.MAX_FOLDER_DEPTH * 2) {
            if (cursor == ancestorId) return true
            cursor = byId[cursor]?.parentId
        }
        return false
    }

    /** Height of [folderId]'s subtree in levels (a folder with no subfolders = 1). */
    fun subtreeHeight(
        folderId: String,
        folders: List<Folder>,
    ): Int {
        val childrenOf = folders.groupBy { it.parentId }

        fun height(
            id: String,
            guard: Int,
        ): Int =
            if (guard > FileItem.MAX_FOLDER_DEPTH * 2) 1 else 1 + (childrenOf[id].orEmpty().maxOfOrNull { height(it.id, guard + 1) } ?: 0)
        return height(folderId, 0)
    }

    /**
     * Validates moving [folder] under [newParentId] (null = top level): same place, cycle
     * (self or descendant), depth cap (the moved subtree's deepest level must stay
     * ≤ [FileItem.MAX_FOLDER_DEPTH]) and a live case-insensitive sibling name clash.
     */
    fun validateFolderMove(
        folder: Folder,
        newParentId: String?,
        liveFolders: List<Folder>,
    ): MoveError? {
        if (newParentId == folder.parentId) return MoveError.SAME_LOCATION
        if (newParentId != null &&
            (newParentId == folder.id || isDescendant(newParentId, folder.id, liveFolders))
        ) {
            return MoveError.INTO_SELF
        }
        if (depth(newParentId, liveFolders) + subtreeHeight(folder.id, liveFolders) > FileItem.MAX_FOLDER_DEPTH) return MoveError.TOO_DEEP
        if (validateFolderName(folder.name, newParentId, liveFolders, excludeFolderId = folder.id) == FolderNameError.DUPLICATE) {
            return MoveError.DUPLICATE
        }
        return null
    }

    /** Validates moving a file into [newFolderId] — only "already there" can fail (names may repeat). */
    fun validateFileMove(
        file: FileItem,
        newFolderId: String?,
    ): MoveError? = if (newFolderId == file.folderId) MoveError.SAME_LOCATION else null

    /** Ids of every ancestor of [folderId] (the path to pre-expand so a preselected row is visible). */
    fun ancestorIds(
        folderId: String?,
        folders: List<Folder>,
    ): Set<String> = breadcrumbs(folderId, folders).dropLast(1).map { it.id }.toSet()

    /**
     * Visible rows of the LAZY picker: top-level folders A–Z; a row's children (A–Z,
     * indented one level) appear only while its id is in [expanded]. Rows report
     * [PickerRow.hasChildren] so the UI can draw an expand chevron only where it matters.
     * [excludeSubtreeOf] hides that folder AND everything under it — the folder being
     * MOVED can never be its own destination (hidden, not greyed; web parity).
     */
    fun pickerRows(
        folders: List<Folder>,
        expanded: Set<String>,
        excludeSubtreeOf: String? = null,
    ): List<PickerRow> {
        val childrenOf =
            folders
                .filter { it.id != excludeSubtreeOf }
                .groupBy { it.parentId }
                .mapValues { (_, v) -> v.sortedBy { it.name.lowercase() } }
        val out = mutableListOf<PickerRow>()

        fun visit(
            parentId: String?,
            depth: Int,
        ) {
            if (depth > FileItem.MAX_FOLDER_DEPTH * 2) return
            childrenOf[parentId].orEmpty().forEach { folder ->
                val kids = childrenOf[folder.id].orEmpty()
                val isExpanded = kids.isNotEmpty() && folder.id in expanded
                out += PickerRow(folder, depth + 1, hasChildren = kids.isNotEmpty(), expanded = isExpanded)
                if (isExpanded) visit(folder.id, depth + 1)
            }
        }
        visit(null, 0)
        return out
    }

    /**
     * Validates a candidate folder name against the server rules and the LIVE siblings
     * under [parentId] (case-insensitive; [excludeFolderId] = the folder being renamed).
     */
    fun validateFolderName(
        raw: String,
        parentId: String?,
        liveFolders: List<Folder>,
        excludeFolderId: String? = null,
    ): FolderNameError? {
        val name = raw.trim()
        if (name.isEmpty()) return FolderNameError.REQUIRED
        if (name.length > MAX_FOLDER_NAME || name.contains('/')) return FolderNameError.INVALID
        val duplicate =
            liveFolders.any {
                it.id != excludeFolderId && it.parentId == parentId && it.deletedAt == null && it.name.equals(name, ignoreCase = true)
            }
        return if (duplicate) FolderNameError.DUPLICATE else null
    }

    /** Root-first chain of folders from the top level down to [folderId] (empty at the top). */
    fun breadcrumbs(
        folderId: String?,
        folders: List<Folder>,
    ): List<Folder> {
        val byId = folders.associateBy { it.id }
        val chain = ArrayDeque<Folder>()
        var cursor = folderId?.let(byId::get)
        var guard = 0
        while (cursor != null && guard++ < FileItem.MAX_FOLDER_DEPTH * 2) {
            chain.addFirst(cursor)
            cursor = cursor.parentId?.let(byId::get)
        }
        return chain.toList()
    }

    /** Depth of [folderId] (0 = top level); new subfolders are refused past [FileItem.MAX_FOLDER_DEPTH]. */
    fun depth(
        folderId: String?,
        folders: List<Folder>,
    ): Int = breadcrumbs(folderId, folders).size

    /** Whether a subfolder may be created under [parentId] (design D13, depth ≤ [FileItem.MAX_FOLDER_DEPTH]). */
    fun canCreateSubfolder(
        parentId: String?,
        folders: List<Folder>,
    ): Boolean = depth(parentId, folders) < FileItem.MAX_FOLDER_DEPTH

    /** Direct live children (subfolders + files) of [folderId]. */
    fun directChildCount(
        folderId: String,
        folders: List<Folder>,
        files: List<FileWithLocalState>,
    ): Int = folders.count { it.parentId == folderId } + files.count { it.file.folderId == folderId }

    /** Every live descendant (subfolders + files, recursive) of [folderId] — the delete-confirm count. */
    fun subtreeCount(
        folderId: String,
        folders: List<Folder>,
        files: List<FileWithLocalState>,
    ): Int {
        val childrenOf = folders.groupBy { it.parentId }
        var count = 0

        fun visit(id: String) {
            val subs = childrenOf[id].orEmpty()
            count += subs.size + files.count { it.file.folderId == id }
            subs.forEach { visit(it.id) }
        }
        visit(folderId)
        return count
    }

    /**
     * Whether [folderId]'s ancestor chain is intact — a file/folder whose parent was
     * tombstoned (no server cascade, design D2) is hidden client-side.
     */
    fun isReachable(
        folderId: String?,
        folders: List<Folder>,
    ): Boolean {
        if (folderId == null) return true
        val byId = folders.associateBy { it.id }
        var cursor = byId[folderId] ?: return false
        var guard = 0
        while (guard++ < FileItem.MAX_FOLDER_DEPTH * 2) {
            val parent = cursor.parentId ?: return true
            cursor = byId[parent] ?: return false
        }
        return false
    }

    /** Case-insensitive name-substring search across the WHOLE accessible index (design D11). */
    fun matches(
        query: String,
        name: String,
    ): Boolean {
        val q = query.trim()
        return q.isNotEmpty() && name.contains(q, ignoreCase = true)
    }

    /** `All files › Contracts › 2026` — the path string of a folder (or the root label alone). */
    fun pathLabel(
        folderId: String?,
        folders: List<Folder>,
        rootLabel: String,
        separator: String,
    ): String = (listOf(rootLabel) + breadcrumbs(folderId, folders).map { it.name }).joinToString(" $separator ")
}
