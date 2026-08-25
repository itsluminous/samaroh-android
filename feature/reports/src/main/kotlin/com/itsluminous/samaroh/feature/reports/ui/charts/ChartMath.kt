package com.itsluminous.samaroh.feature.reports.ui.charts

/** Pure geometry for the hand-rolled charts, kept out of the Canvas so it is unit-testable. */
object ChartMath {
    /**
     * Maps a tap x offset inside the plot area to a bar index, or null when the tap is
     * outside the plot or there is nothing to hit.
     */
    fun barIndex(
        x: Float,
        plotWidth: Float,
        barCount: Int,
    ): Int? {
        if (barCount <= 0 || plotWidth <= 0f || x < 0f || x >= plotWidth) return null
        return ((x / plotWidth) * barCount).toInt().coerceAtMost(barCount - 1)
    }

    /**
     * Value the tallest stacked bar is scaled against. Never zero, so all-zero data draws
     * flat bars instead of dividing by zero.
     */
    fun maxStackValue(entries: List<ChartEntry>): Long =
        entries.maxOfOrNull { entry -> entry.values.sumOf { it.coerceAtLeast(0L) } }?.takeIf { it > 0L } ?: 1L

    /**
     * Line-chart vertical bounds: always includes zero so a profit line has a baseline to
     * cross, and never collapses to a zero-height span.
     */
    fun lineBounds(values: List<Long>): Pair<Long, Long> {
        val min = minOf(0L, values.minOrNull() ?: 0L)
        val max = maxOf(0L, values.maxOrNull() ?: 0L)
        return if (min == max) min to (max + 1L) else min to max
    }
}

/** One bar of a (possibly stacked) bar chart; [values] align with the chart's colors/legends. */
data class ChartEntry(
    /** Short axis label under the bar (for example an abbreviated month). */
    val label: String,
    /** Full label used in the selection details and accessibility descriptions. */
    val fullLabel: String,
    val values: List<Long>,
)

/** One slice of a pie chart. */
data class PieSlice(
    val label: String,
    val value: Long,
)
