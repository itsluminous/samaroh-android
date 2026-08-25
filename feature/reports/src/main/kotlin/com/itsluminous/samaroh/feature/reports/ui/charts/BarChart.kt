package com.itsluminous.samaroh.feature.reports.ui.charts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.i18n.R

internal val CHART_HEIGHT = 160.dp
internal val AXIS_WIDTH = 52.dp
internal const val GRID_STEPS = 3
private const val DIMMED_BAR_ALPHA = 0.35f

/**
 * Hand-rolled Compose Canvas bar chart: one (optionally stacked) bar per entry with a
 * compact y-axis, faint gridlines, a color key and tappable bars that reveal the entry's
 * exact values. No chart library.
 */
@Composable
fun SamarohBarChart(
    entries: List<ChartEntry>,
    colors: List<Color>,
    legends: List<String>,
    axisFormatter: (Long) -> String,
    valueFormatter: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val growth by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "bar_growth",
    )

    var selectedIndex by rememberSaveable(entries.size) { mutableIntStateOf(-1) }

    fun toggleSelection(index: Int) {
        selectedIndex = if (selectedIndex == index) -1 else index
    }

    // Entries arrive pre-aggregated from the calculators; only cheap scaling happens here.
    val maxValue = remember(entries) { ChartMath.maxStackValue(entries) }
    val chartSummary =
        if (entries.isEmpty()) {
            stringResource(R.string.reports_chart_empty)
        } else {
            stringResource(R.string.reports_chart_summary, entries.first().fullLabel, entries.last().fullLabel)
        }

    Column(modifier = modifier.fillMaxWidth()) {
        SelectionDetailsRow(
            selected = entries.getOrNull(selectedIndex),
            legends = legends,
            valueFormatter = valueFormatter,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            AxisLabels(maxValue = maxValue, axisFormatter = axisFormatter)
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(CHART_HEIGHT)
                        .semantics { contentDescription = chartSummary },
            ) {
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(entries) {
                                detectTapGestures { offset ->
                                    ChartMath
                                        .barIndex(offset.x, size.width.toFloat(), entries.size)
                                        ?.let(::toggleSelection)
                                }
                            },
                ) {
                    // Faint horizontal gridlines matching the axis labels.
                    val gridStroke = 1.dp.toPx()
                    for (line in 0..GRID_STEPS) {
                        val y = size.height * line / GRID_STEPS
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = gridStroke,
                        )
                    }
                    if (entries.isEmpty()) return@Canvas

                    val groupWidth = size.width / entries.size
                    val barWidth = (groupWidth * 0.55f).coerceAtMost(40.dp.toPx())
                    val corner = CornerRadius(4.dp.toPx())
                    val hasSelection = selectedIndex in entries.indices

                    if (hasSelection) {
                        // Tonal highlight behind the selected bar.
                        drawRoundRect(
                            color = gridColor.copy(alpha = 0.5f),
                            topLeft = Offset(groupWidth * selectedIndex, 0f),
                            size = Size(groupWidth, size.height),
                            cornerRadius = corner,
                        )
                    }

                    entries.forEachIndexed { index, entry ->
                        val alpha = if (!hasSelection || index == selectedIndex) 1f else DIMMED_BAR_ALPHA
                        val left = groupWidth * index + (groupWidth - barWidth) / 2f
                        // Stack segments bottom-up in color order.
                        var top = size.height
                        entry.values.forEachIndexed { segment, value ->
                            val segmentHeight = (value.coerceAtLeast(0L).toFloat() / maxValue * size.height * growth)
                            top -= segmentHeight
                            drawRoundRect(
                                color = colors[segment % colors.size].copy(alpha = alpha),
                                topLeft = Offset(left, top),
                                size = Size(barWidth, segmentHeight),
                                cornerRadius = corner,
                            )
                        }
                    }
                }
                // One focusable, labelled semantics node per bar for TalkBack; pointer
                // events still reach the Canvas underneath.
                Row(modifier = Modifier.fillMaxSize()) {
                    entries.forEachIndexed { index, entry ->
                        val barLabel = selectionDetails(entry, legends, valueFormatter)
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .semantics {
                                        contentDescription = barLabel
                                        onClick {
                                            toggleSelection(index)
                                            true
                                        }
                                    },
                        )
                    }
                }
            }
        }
        if (entries.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Spacer(Modifier.width(AXIS_WIDTH))
                entries.forEach { entry ->
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        ChartColorKey(colors = colors, legends = legends)
    }
}

@Composable
internal fun AxisLabels(
    maxValue: Long,
    axisFormatter: (Long) -> String,
    minValue: Long = 0L,
) {
    Column(
        modifier = Modifier.width(AXIS_WIDTH).height(CHART_HEIGHT),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        for (line in GRID_STEPS downTo 0) {
            val value = minValue + (maxValue - minValue) * line / GRID_STEPS
            Text(
                text = axisFormatter(value),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

/** "{legend}: {value}" pairs joined for the selection banner and bar descriptions. */
@Composable
internal fun selectionDetails(
    entry: ChartEntry,
    legends: List<String>,
    valueFormatter: (Long) -> String,
): String {
    val pairs =
        entry.values.mapIndexed { index, value ->
            stringResource(R.string.reports_chart_legend_value, legends.getOrElse(index) { "" }, valueFormatter(value))
        }
    return (listOf(entry.fullLabel) + pairs).joinToString(" · ")
}

@Composable
internal fun SelectionDetailsRow(
    selected: ChartEntry?,
    legends: List<String>,
    valueFormatter: (Long) -> String,
) {
    AnimatedVisibility(visible = selected != null) {
        // Remember the last non-null value so the exit animation has content.
        var shown by remember { mutableStateOf(selected) }
        if (selected != null) shown = selected
        shown?.let { entry ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    text = selectionDetails(entry, legends, valueFormatter),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
internal fun ChartColorKey(
    colors: List<Color>,
    legends: List<String>,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        legends.forEachIndexed { index, legend ->
            KeyEntry(color = colors[index % colors.size], label = legend)
        }
    }
}

@Composable
private fun KeyEntry(
    color: Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(8.dp).background(color = color, shape = CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
