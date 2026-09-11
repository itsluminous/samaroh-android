package com.itsluminous.samaroh.feature.notes.domain

/**
 * Pure checklist reorder (feedback batch, replaces ADR-077's move-up button): the
 * editor's drag-to-reorder gesture translates into [move] calls, one per crossed
 * neighbour midpoint. Pure and index-based so the maths is unit-testable without
 * Compose.
 */
object ChecklistReorder {
    /**
     * Returns [items] with the element at [fromIndex] moved to [toIndex]
     * (clamped to the list bounds). Out-of-range [fromIndex] or a no-op move
     * returns the list unchanged.
     */
    fun <T> move(
        items: List<T>,
        fromIndex: Int,
        toIndex: Int,
    ): List<T> {
        if (fromIndex !in items.indices) return items
        val target = toIndex.coerceIn(0, items.lastIndex)
        if (target == fromIndex) return items
        val result = items.toMutableList()
        val moved = result.removeAt(fromIndex)
        result.add(target, moved)
        return result
    }
}
