package com.itsluminous.samaroh.feature.files.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.feature.files.fileFixture
import com.itsluminous.samaroh.feature.files.folderFixture
import org.junit.Test

/** Pure tree helpers (ADR-085): name validation, breadcrumbs, counts, reachability, search. */
class FilesTreeTest {
    private val root = folderFixture("root", name = "Contracts")
    private val child = folderFixture("child", name = "2026", parentId = "root")
    private val grandchild = folderFixture("gc", name = "Q1", parentId = "child")
    private val sibling = folderFixture("sib", name = "Photos")
    private val folders = listOf(root, child, grandchild, sibling)
    private val files =
        listOf(
            fileFixture("f-root", folderId = "root", name = "lease.pdf", mimeType = "application/pdf"),
            fileFixture("f-gc", folderId = "gc", name = "scan.jpg"),
            fileFixture("f-top", folderId = null, name = "logo.png"),
        )

    @Test
    fun `folder name validation mirrors the server rules and live case-insensitive duplicates`() {
        assertThat(FilesTree.validateFolderName("  ", null, folders)).isEqualTo(FolderNameError.REQUIRED)
        assertThat(FilesTree.validateFolderName("a/b", null, folders)).isEqualTo(FolderNameError.INVALID)
        assertThat(FilesTree.validateFolderName("x".repeat(121), null, folders)).isEqualTo(FolderNameError.INVALID)
        assertThat(FilesTree.validateFolderName("contracts", null, folders)).isEqualTo(FolderNameError.DUPLICATE)
        // Same name in ANOTHER parent is fine; renaming a folder to its own name is fine.
        assertThat(FilesTree.validateFolderName("Contracts", "root", folders)).isNull()
        assertThat(FilesTree.validateFolderName("CONTRACTS", null, folders, excludeFolderId = "root")).isNull()
        // A tombstoned twin never blocks (ADR-083 lesson).
        val withTombstone = folders + folderFixture("dead", name = "Old").copy(deletedAt = root.createdAt)
        assertThat(FilesTree.validateFolderName("old", null, withTombstone)).isNull()
        assertThat(FilesTree.validateFolderName(" Videos ", null, folders)).isNull()
    }

    @Test
    fun `breadcrumbs walk root-first and depth follows`() {
        assertThat(FilesTree.breadcrumbs("gc", folders).map { it.id }).containsExactly("root", "child", "gc").inOrder()
        assertThat(FilesTree.breadcrumbs(null, folders)).isEmpty()
        assertThat(FilesTree.depth("gc", folders)).isEqualTo(3)
        assertThat(FilesTree.depth(null, folders)).isEqualTo(0)
    }

    @Test
    fun `direct and recursive counts`() {
        assertThat(FilesTree.directChildCount("root", folders, files)).isEqualTo(2) // child folder + lease.pdf
        assertThat(FilesTree.directChildCount("sib", folders, files)).isEqualTo(0)
        assertThat(FilesTree.subtreeCount("root", folders, files)).isEqualTo(4) // child, gc, lease.pdf, scan.jpg
        assertThat(FilesTree.subtreeCount("child", folders, files)).isEqualTo(2)
    }

    @Test
    fun `rows under a tombstoned ancestor are unreachable`() {
        val deadChild = folders.map { if (it.id == "child") it.copy(deletedAt = it.createdAt) else it }.filter { it.deletedAt == null }
        assertThat(FilesTree.isReachable("gc", deadChild)).isFalse()
        assertThat(FilesTree.isReachable("root", deadChild)).isTrue()
        assertThat(FilesTree.isReachable(null, deadChild)).isTrue()
        assertThat(FilesTree.isReachable("missing", folders)).isFalse()
    }

    @Test
    fun `search is a case-insensitive substring and the path label joins with the separator`() {
        assertThat(FilesTree.matches("LEASE", "lease.pdf")).isTrue()
        assertThat(FilesTree.matches("  ", "lease.pdf")).isFalse()
        assertThat(FilesTree.matches("zzz", "lease.pdf")).isFalse()
        assertThat(FilesTree.pathLabel("gc", folders, "All files", "›")).isEqualTo("All files › Contracts › 2026 › Q1")
        assertThat(FilesTree.pathLabel(null, folders, "All files", "›")).isEqualTo("All files")
    }
}
