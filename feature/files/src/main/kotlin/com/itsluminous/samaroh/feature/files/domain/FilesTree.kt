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

/** Pure Files-tree helpers over the per-business index (ADR-085) — unit-tested, no I/O. */
object FilesTree {
    /** Server CHECK: 1–120 chars after trim, no `/`. */
    const val MAX_FOLDER_NAME = 120

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
