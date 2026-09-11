package com.itsluminous.samaroh.feature.notes.domain

/**
 * Pure math of the whole-row checklist drag (ADR-082; semantics match the web
 * track's `rowDrag.ts`): while a long-pressed row follows the finger, its TARGET
 * slot advances every time the displacement crosses a neighbour's midpoint, and
 * the non-dragged rows shift out of the way; the reorder itself commits once, on
 * drop. Kept free of Compose so the midpoint-crossing and gap-opening maths are
 * unit-testable.
 */
object RowDrag {
    /**
     * Midpoint-crossing reorder target: with the dragged row displaced [dy] pixels
     * from its start, the row settles at the last neighbour whose midpoint the
     * displacement has crossed. [heights] are the row heights (px) in the PRE-drag
     * order. An out-of-range [from] returns itself.
     */
    fun targetIndexFor(
        from: Int,
        dy: Float,
        heights: List<Int>,
    ): Int {
        if (from < 0 || from >= heights.size) return from
        var target = from
        if (dy > 0f) {
            var acc = 0f
            for (j in from + 1 until heights.size) {
                val h = heights[j]
                if (dy >= acc + h / 2f) {
                    target = j
                    acc += h
                } else {
                    break
                }
            }
        } else if (dy < 0f) {
            var acc = 0f
            for (j in from - 1 downTo 0) {
                val h = heights[j]
                if (-dy >= acc + h / 2f) {
                    target = j
                    acc += h
                } else {
                    break
                }
            }
        }
        return target
    }

    /**
     * Vertical shift (px) a NON-dragged row takes while the drag hovers at
     * [target]: rows between the pickup index and the target slide by the dragged
     * row's height to open the gap. The dragged row itself is 0 here — it follows
     * the finger instead.
     */
    fun rowShift(
        rowIndex: Int,
        from: Int,
        target: Int,
        draggedHeight: Int,
    ): Int =
        when {
            rowIndex == from -> 0
            from < target && rowIndex > from && rowIndex <= target -> -draggedHeight
            from > target && rowIndex >= target && rowIndex < from -> draggedHeight
            else -> 0
        }
}
