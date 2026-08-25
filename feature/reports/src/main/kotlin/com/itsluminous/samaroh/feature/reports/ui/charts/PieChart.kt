package com.itsluminous.samaroh.feature.reports.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.i18n.R

/**
 * Hand-rolled Compose Canvas donut chart with a legend listing each slice's value and
 * share. No chart library.
 */
@Composable
fun SamarohPieChart(
    slices: List<PieSlice>,
    colors: List<Color>,
    valueFormatter: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val sweepReveal by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "pie_reveal",
    )

    val total = remember(slices) { slices.sumOf { it.value.coerceAtLeast(0L) }.takeIf { it > 0L } ?: 1L }
    val chartSummary =
        if (slices.isEmpty()) {
            stringResource(R.string.reports_chart_empty)
        } else {
            stringResource(R.string.reports_chart_summary, slices.first().label, slices.last().label)
        }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(modifier = Modifier.size(140.dp).semantics { contentDescription = chartSummary }) {
            Canvas(modifier = Modifier.size(140.dp)) {
                val stroke = Stroke(width = 26.dp.toPx())
                val inset = stroke.width / 2f
                val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
                var startAngle = -90f
                slices.forEachIndexed { index, slice ->
                    val sweep = slice.value.coerceAtLeast(0L).toFloat() / total * 360f * sweepReveal
                    drawArc(
                        color = colors[index % colors.size],
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = stroke,
                    )
                    startAngle += sweep
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            slices.forEachIndexed { index, slice ->
                val percent = (slice.value.coerceAtLeast(0L) * 100 / total).toInt()
                val percentText = stringResource(R.string.reports_format_percent, percent)
                val valueText =
                    stringResource(
                        R.string.reports_chart_legend_value,
                        slice.label,
                        valueFormatter(slice.value),
                    )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(color = colors[index % colors.size], shape = CircleShape),
                    )
                    Text(
                        text = "$valueText ($percentText)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
