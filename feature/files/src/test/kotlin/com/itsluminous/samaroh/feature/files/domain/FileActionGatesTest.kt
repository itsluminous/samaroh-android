package com.itsluminous.samaroh.feature.files.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ADR-090 gating — mirrors the server `files_update` policy: delete-holders, or own upload with upload. */
class FileActionGatesTest {
    @Test
    fun `delete holders may rename or move any file`() {
        assertThat(
            FileActionGates.canRenameOrMoveFile(canDelete = true, canUpload = false, fileCreatedBy = "other", userId = "me"),
        ).isTrue()
    }

    @Test
    fun `uploaders may rename or move only their own files`() {
        assertThat(FileActionGates.canRenameOrMoveFile(canDelete = false, canUpload = true, fileCreatedBy = "me", userId = "me")).isTrue()
        assertThat(
            FileActionGates.canRenameOrMoveFile(canDelete = false, canUpload = true, fileCreatedBy = "other", userId = "me"),
        ).isFalse()
    }

    @Test
    fun `viewers and unknown users get nothing`() {
        assertThat(FileActionGates.canRenameOrMoveFile(canDelete = false, canUpload = false, fileCreatedBy = "me", userId = "me")).isFalse()
        assertThat(FileActionGates.canRenameOrMoveFile(canDelete = false, canUpload = true, fileCreatedBy = "me", userId = null)).isFalse()
    }

    @Test
    fun `folder rename or move follows the effective manage_folders flag`() {
        assertThat(FileActionGates.canRenameOrMoveFolder(canManageFolders = true)).isTrue()
        assertThat(FileActionGates.canRenameOrMoveFolder(canManageFolders = false)).isFalse()
    }
}
