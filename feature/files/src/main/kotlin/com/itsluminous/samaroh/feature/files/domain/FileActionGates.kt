package com.itsluminous.samaroh.feature.files.domain

/**
 * Per-item action gates that go beyond the module-level flags (ADR-090, design D14
 * revised) — pure, unit-tested, and byte-for-byte the server's `files_update` policy so
 * no affordance the UI shows can be rejected by RLS later.
 */
object FileActionGates {
    /**
     * Rename / Move of a FILE: `files.delete` holders may touch any file; otherwise the
     * member must hold `files.upload` AND be the file's uploader. Owners already pass
     * `canDelete` (the session folds owner into every gate), and so does the
     * signed-out/offline default (every gate true).
     */
    fun canRenameOrMoveFile(
        canDelete: Boolean,
        canUpload: Boolean,
        fileCreatedBy: String,
        userId: String?,
    ): Boolean = canDelete || (canUpload && userId != null && fileCreatedBy == userId)

    /** Rename / Move of a FOLDER: the effective `files.manage_folders` (inherits `upload`, D5). */
    fun canRenameOrMoveFolder(canManageFolders: Boolean): Boolean = canManageFolders
}
