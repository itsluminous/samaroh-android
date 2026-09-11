package com.itsluminous.samaroh.feature.notes.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure move maths behind the checklist drag reorder (ADR-081). */
class ChecklistReorderTest {
    private val items = listOf("a", "b", "c", "d")

    @Test
    fun `moves an item down`() {
        assertThat(ChecklistReorder.move(items, 0, 2)).containsExactly("b", "c", "a", "d").inOrder()
    }

    @Test
    fun `moves an item up`() {
        assertThat(ChecklistReorder.move(items, 3, 1)).containsExactly("a", "d", "b", "c").inOrder()
    }

    @Test
    fun `swaps adjacent neighbours both ways`() {
        assertThat(ChecklistReorder.move(items, 1, 2)).containsExactly("a", "c", "b", "d").inOrder()
        assertThat(ChecklistReorder.move(items, 2, 1)).containsExactly("a", "c", "b", "d").inOrder()
    }

    @Test
    fun `clamps the target index to the list bounds`() {
        assertThat(ChecklistReorder.move(items, 1, 99)).containsExactly("a", "c", "d", "b").inOrder()
        assertThat(ChecklistReorder.move(items, 2, -5)).containsExactly("c", "a", "b", "d").inOrder()
    }

    @Test
    fun `same index and invalid from are no-ops`() {
        assertThat(ChecklistReorder.move(items, 2, 2)).isEqualTo(items)
        assertThat(ChecklistReorder.move(items, -1, 0)).isEqualTo(items)
        assertThat(ChecklistReorder.move(items, 4, 0)).isEqualTo(items)
        assertThat(ChecklistReorder.move(emptyList<String>(), 0, 1)).isEmpty()
    }
}
