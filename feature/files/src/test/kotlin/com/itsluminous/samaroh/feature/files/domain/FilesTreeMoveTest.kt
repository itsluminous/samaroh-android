package com.itsluminous.samaroh.feature.files.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.FileItem
import com.itsluminous.samaroh.feature.files.fileFixture
import com.itsluminous.samaroh.feature.files.folderFixture
import org.junit.Test

/** ADR-090: file-name rule, move validation (same place / cycle / depth / duplicate) and the lazy picker tree. */
class FilesTreeMoveTest {
    // a / a1 / a1x ; b ; c (A–Z: "Alpha", "Bravo", "Charlie")
    private val a = folderFixture("a", name = "Alpha")
    private val a1 = folderFixture("a1", name = "Alpha one", parentId = "a")
    private val a1x = folderFixture("a1x", name = "Deep", parentId = "a1")
    private val b = folderFixture("b", name = "Bravo")
    private val c = folderFixture("c", name = "Charlie")
    private val tree = listOf(c, a, b, a1, a1x)

    @Test
    fun `file names must be 1-255 chars without a slash but may repeat`() {
        assertThat(FilesTree.validateFileName("  ")).isEqualTo(FileNameError.REQUIRED)
        assertThat(FilesTree.validateFileName("a/b.pdf")).isEqualTo(FileNameError.INVALID)
        assertThat(FilesTree.validateFileName("x".repeat(256))).isEqualTo(FileNameError.INVALID)
        assertThat(FilesTree.validateFileName("x".repeat(255))).isNull()
        assertThat(FilesTree.validateFileName(" lease.pdf ")).isNull()
    }

    @Test
    fun `folder move refuses the same parent, itself and its own descendants`() {
        assertThat(FilesTree.validateFolderMove(a1, "a", tree)).isEqualTo(MoveError.SAME_LOCATION)
        assertThat(FilesTree.validateFolderMove(a, null, tree)).isEqualTo(MoveError.SAME_LOCATION)
        assertThat(FilesTree.validateFolderMove(a, "a", tree)).isEqualTo(MoveError.INTO_SELF)
        assertThat(FilesTree.validateFolderMove(a, "a1x", tree)).isEqualTo(MoveError.INTO_SELF)
        assertThat(FilesTree.validateFolderMove(a1, "b", tree)).isNull()
        assertThat(FilesTree.validateFolderMove(a1x, null, tree)).isNull()
    }

    @Test
    fun `folder move refuses a live case-insensitive sibling name clash at the destination`() {
        val twin = folderFixture("twin", name = "alpha ONE", parentId = "b")
        assertThat(FilesTree.validateFolderMove(a1, "b", tree + twin)).isEqualTo(MoveError.DUPLICATE)
        // A tombstoned twin does not block (LIVE rows only, design D12).
        val dead = twin.copy(deletedAt = twin.createdAt)
        assertThat(FilesTree.validateFolderMove(a1, "b", tree + dead)).isNull()
    }

    @Test
    fun `folder move respects the depth cap for the whole moved subtree`() {
        // chain d1..d8 (8 levels deep); moving "a" (height 3: a / a1 / a1x) under d7 → 10 ok, under d8 → 11 refused
        val chain = (1..8).map { i -> folderFixture("d$i", name = "D$i", parentId = if (i == 1) null else "d${i - 1}") }
        val all = tree + chain
        assertThat(FilesTree.subtreeHeight("a", all)).isEqualTo(3)
        assertThat(FilesTree.depth("d7", all)).isEqualTo(7)
        assertThat(FilesTree.validateFolderMove(a, "d7", all)).isNull()
        assertThat(FilesTree.validateFolderMove(a, "d8", all)).isEqualTo(MoveError.TOO_DEEP)
        assertThat(FileItem.MAX_FOLDER_DEPTH).isEqualTo(10)
    }

    @Test
    fun `file move only refuses the folder it is already in`() {
        val file = fileFixture("f", folderId = "a").file
        assertThat(FilesTree.validateFileMove(file, "a")).isEqualTo(MoveError.SAME_LOCATION)
        assertThat(FilesTree.validateFileMove(file, null)).isNull()
        assertThat(FilesTree.validateFileMove(file, "b")).isNull()
        assertThat(FilesTree.validateFileMove(fileFixture("g").file, null)).isEqualTo(MoveError.SAME_LOCATION)
    }

    @Test
    fun `lazy picker shows only roots until a row with children is expanded`() {
        val collapsed = FilesTree.pickerRows(tree, expanded = emptySet())
        assertThat(collapsed.map { it.folder.name }).containsExactly("Alpha", "Bravo", "Charlie").inOrder()
        assertThat(collapsed.map { it.depth }).containsExactly(1, 1, 1)
        assertThat(collapsed.map { it.hasChildren }).containsExactly(true, false, false).inOrder()
        assertThat(collapsed.none { it.expanded }).isTrue()

        val oneLevel = FilesTree.pickerRows(tree, expanded = setOf("a"))
        assertThat(oneLevel.map { it.folder.name }).containsExactly("Alpha", "Alpha one", "Bravo", "Charlie").inOrder()
        assertThat(oneLevel.single { it.folder.id == "a" }.expanded).isTrue()
        assertThat(oneLevel.single { it.folder.id == "a1" }.depth).isEqualTo(2)
        assertThat(oneLevel.single { it.folder.id == "a1" }.hasChildren).isTrue()

        // Expanding a grandchild without its parent reveals nothing extra.
        assertThat(FilesTree.pickerRows(tree, expanded = setOf("a1")).map { it.folder.id }).containsExactly("a", "b", "c").inOrder()

        val twoLevels = FilesTree.pickerRows(tree, expanded = setOf("a", "a1"))
        assertThat(twoLevels.map { it.folder.id }).containsExactly("a", "a1", "a1x", "b", "c").inOrder()
        assertThat(twoLevels.single { it.folder.id == "a1x" }.depth).isEqualTo(3)
        assertThat(twoLevels.single { it.folder.id == "a1x" }.hasChildren).isFalse()
    }

    @Test
    fun `lazy picker hides the moved folder and its whole subtree (web parity)`() {
        // Moving "Alpha": neither it nor Alpha one / a1x may be offered as a destination,
        // even when they are expanded; the siblings stay.
        val rows = FilesTree.pickerRows(tree, expanded = setOf("a", "a1"), excludeSubtreeOf = "a")
        assertThat(rows.map { it.folder.id }).containsExactly("b", "c").inOrder()
        // Moving a nested folder hides only its own subtree; the parent keeps its other children.
        val nested = FilesTree.pickerRows(tree, expanded = setOf("a", "a1"), excludeSubtreeOf = "a1")
        assertThat(nested.map { it.folder.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(nested.single { it.folder.id == "a" }.hasChildren).isFalse()
        // No exclusion → unchanged.
        assertThat(FilesTree.pickerRows(tree, expanded = emptySet(), excludeSubtreeOf = null).map { it.folder.id })
            .containsExactly("a", "b", "c")
            .inOrder()
    }

    @Test
    fun `ancestor ids pre-expand the path to a preselected destination`() {
        assertThat(FilesTree.ancestorIds("a1x", tree)).containsExactly("a", "a1")
        assertThat(FilesTree.ancestorIds("a", tree)).isEmpty()
        assertThat(FilesTree.ancestorIds(null, tree)).isEmpty()
    }

    @Test
    fun `descendant walk is bounded against a corrupt cycle`() {
        val loopA = folderFixture("la", name = "LA", parentId = "lb")
        val loopB = folderFixture("lb", name = "LB", parentId = "la")
        assertThat(FilesTree.isDescendant("la", "zzz", listOf(loopA, loopB))).isFalse()
        assertThat(FilesTree.subtreeHeight("la", listOf(loopA, loopB))).isAtMost(FileItem.MAX_FOLDER_DEPTH * 2 + 2)
    }
}
