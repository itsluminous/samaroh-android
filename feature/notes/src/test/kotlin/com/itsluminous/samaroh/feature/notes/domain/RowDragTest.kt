package com.itsluminous.samaroh.feature.notes.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure maths of the whole-row checklist drag (ADR-082): midpoint-crossing target
 * resolution and the gap-opening shifts of the non-dragged rows — semantics match
 * the web track's `rowDrag.ts` unit suite.
 */
class RowDragTest {
    private val uniform = listOf(100, 100, 100, 100)

    // ---- targetIndexFor ----

    @Test
    fun `no displacement keeps the row in place`() {
        assertThat(RowDrag.targetIndexFor(1, 0f, uniform)).isEqualTo(1)
    }

    @Test
    fun `downward drag crosses the next midpoint at half its height`() {
        // 49px: not yet past row 2's midpoint (50).
        assertThat(RowDrag.targetIndexFor(1, 49f, uniform)).isEqualTo(1)
        // 50px: exactly the midpoint — crossed.
        assertThat(RowDrag.targetIndexFor(1, 50f, uniform)).isEqualTo(2)
        // Two rows down needs 100 (row 2) + 50 (row 3 midpoint).
        assertThat(RowDrag.targetIndexFor(1, 149f, uniform)).isEqualTo(2)
        assertThat(RowDrag.targetIndexFor(1, 150f, uniform)).isEqualTo(3)
    }

    @Test
    fun `upward drag mirrors the midpoint rule`() {
        assertThat(RowDrag.targetIndexFor(2, -49f, uniform)).isEqualTo(2)
        assertThat(RowDrag.targetIndexFor(2, -50f, uniform)).isEqualTo(1)
        assertThat(RowDrag.targetIndexFor(2, -150f, uniform)).isEqualTo(0)
    }

    @Test
    fun `uneven row heights use each neighbour's own midpoint`() {
        val heights = listOf(40, 200, 60)
        // From row 0 downward: row 1's midpoint is at 100.
        assertThat(RowDrag.targetIndexFor(0, 99f, heights)).isEqualTo(0)
        assertThat(RowDrag.targetIndexFor(0, 100f, heights)).isEqualTo(1)
        // Past row 1 (200) + row 2's midpoint (30) = 230.
        assertThat(RowDrag.targetIndexFor(0, 229f, heights)).isEqualTo(1)
        assertThat(RowDrag.targetIndexFor(0, 230f, heights)).isEqualTo(2)
    }

    @Test
    fun `target clamps at the list ends`() {
        assertThat(RowDrag.targetIndexFor(0, 100000f, uniform)).isEqualTo(3)
        assertThat(RowDrag.targetIndexFor(3, -100000f, uniform)).isEqualTo(0)
    }

    @Test
    fun `out-of-range from returns itself`() {
        assertThat(RowDrag.targetIndexFor(-1, 50f, uniform)).isEqualTo(-1)
        assertThat(RowDrag.targetIndexFor(4, 50f, uniform)).isEqualTo(4)
        assertThat(RowDrag.targetIndexFor(0, 10f, emptyList())).isEqualTo(0)
    }

    // ---- rowShift ----

    @Test
    fun `rows between pickup and a downward target slide up by the dragged height`() {
        // Dragging row 0 to slot 2: rows 1 and 2 open the gap upward.
        assertThat(RowDrag.rowShift(rowIndex = 1, from = 0, target = 2, draggedHeight = 100)).isEqualTo(-100)
        assertThat(RowDrag.rowShift(rowIndex = 2, from = 0, target = 2, draggedHeight = 100)).isEqualTo(-100)
        assertThat(RowDrag.rowShift(rowIndex = 3, from = 0, target = 2, draggedHeight = 100)).isEqualTo(0)
    }

    @Test
    fun `rows between an upward target and pickup slide down`() {
        // Dragging row 3 to slot 1: rows 1 and 2 shift down.
        assertThat(RowDrag.rowShift(rowIndex = 1, from = 3, target = 1, draggedHeight = 80)).isEqualTo(80)
        assertThat(RowDrag.rowShift(rowIndex = 2, from = 3, target = 1, draggedHeight = 80)).isEqualTo(80)
        assertThat(RowDrag.rowShift(rowIndex = 0, from = 3, target = 1, draggedHeight = 80)).isEqualTo(0)
    }

    @Test
    fun `the dragged row itself never shifts and target equals from shifts nothing`() {
        assertThat(RowDrag.rowShift(rowIndex = 0, from = 0, target = 2, draggedHeight = 100)).isEqualTo(0)
        (0..3).forEach { row ->
            assertThat(RowDrag.rowShift(rowIndex = row, from = 1, target = 1, draggedHeight = 100)).isEqualTo(0)
        }
    }
}
