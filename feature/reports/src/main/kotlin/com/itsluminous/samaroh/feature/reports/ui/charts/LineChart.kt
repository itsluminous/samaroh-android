package com.itsluminous.samaroh.feature.reports.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.i18n.R

/**
 * Hand-rolled Compose Canvas line chart with a zero baseline (values may be negative —
 * the profit trend crosses it), faint gridlines and per-point dots. No chart library.
 */
@Composable
fun SamarohLineChart(
    entries: List<ChartEntry>,
    legend: String,
    axisFormatter: (Long) -> String,
    valueFormatter: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.onSurfaceVariant

    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val reveal by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "line_reveal",
    )

    val values = entries.map { it.values.firstOrNull() ?: 0L }
    val (minValue, maxValue) = remember(entries) { ChartMath.lineBounds(values) }
    val chartSummary =
        if (entries.isEmpty()) {
            stringResource(R.string.reports_chart_empty)
        } else {
            stringResource(R.string.reports_chart_summary, entries.first().fullLabel, entries.last().fullLabel)
        }
    val pointDescriptions = entries.map { selectionDetails(it, listOf(legend), valueFormatter) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            AxisLabels(maxValue = maxValue, axisFormatter = axisFormatter, minValue = minValue)
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(CHART_HEIGHT)
                        .semantics { contentDescription = chartSummary },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
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

                    val span = (maxValue - minValue).toFloat()

                    fun yFor(value: Long): Float = size.height * (1f - (value - minValue).toFloat() / span)

                    // Emphasized zero baseline so negative months read at a glance.
                    if (minValue < 0L) {
                        drawLine(
                            color = zeroColor,
                            start = Offset(0f, yFor(0L)),
                            end = Offset(size.width, yFor(0L)),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }

                    val stepX = size.width / entries.size
                    val points =
                        values.mapIndexed { index, value ->
                            Offset(stepX * index + stepX / 2f, yFor((value * reveal).toLong()))
                        }
                    val path =
                        Path().apply {
                            points.forEachIndexed { index, point ->
                                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                            }
                        }
                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                    points.forEach { drawCircle(color = lineColor, radius = 3.5.dp.toPx(), center = it) }
                }
                // Per-point semantics for TalkBack.
                Row(modifier = Modifier.fillMaxSize()) {
                    pointDescriptions.forEach { description ->
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .semantics { contentDescription = description },
                        )
                    }
                }
            }
        }
        if (entries.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Spacer(Modifier.width(AXIS_WIDTH))
                entries.forEach { entry ->
                    androidx.compose.material3.Text(
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
        ChartColorKey(colors = listOf(lineColor), legends = listOf(legend))
    }
}
