package com.itsluminous.samaroh.feature.reports.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.reports.domain.AgingBucket
import com.itsluminous.samaroh.feature.reports.ui.charts.ChartEntry
import com.itsluminous.samaroh.feature.reports.ui.charts.CompactAmount
import com.itsluminous.samaroh.feature.reports.ui.charts.PieSlice
import com.itsluminous.samaroh.feature.reports.ui.charts.SamarohBarChart
import com.itsluminous.samaroh.feature.reports.ui.charts.SamarohLineChart
import com.itsluminous.samaroh.feature.reports.ui.charts.SamarohPieChart
import com.itsluminous.samaroh.feature.reports.ui.charts.rememberCompactAmountFormatter
import com.itsluminous.samaroh.feature.reports.ui.currentLocale
import com.itsluminous.samaroh.feature.reports.ui.eventTypeLabel
import com.itsluminous.samaroh.feature.reports.ui.monthAxisLabel
import com.itsluminous.samaroh.feature.reports.ui.monthFullLabel
import com.itsluminous.samaroh.feature.reports.ui.sourceLabel

private const val BAR_LABEL_MAX_CHARS = 7
private const val TOP_BARS = 10

/** Rotating slice/bar palette for categorical charts, drawn from the Material scheme. */
@Composable
private fun chartPalette(): List<Color> =
    with(MaterialTheme.colorScheme) {
        listOf(primary, tertiary, secondary, error, inversePrimary, outline, surfaceTint, secondaryContainer)
    }

/** The right hand-rolled chart for the report's data (§4.4). */
@Composable
fun ReportChart(
    data: ReportData,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val compactAmount = rememberCompactAmountFormatter()
    val money: (Long) -> String = { AmountFormatter.format(it) }
    val plainNumber: (Long) -> String = { it.toString() }
    val moneyIn = SamarohTheme.semanticColors.moneyIn
    val moneyOut = SamarohTheme.semanticColors.moneyOut

    when (data) {
        is ReportData.Revenue ->
            SamarohBarChart(
                entries =
                    data.months.map {
                        ChartEntry(
                            label = monthAxisLabel(it.month, locale),
                            fullLabel = monthFullLabel(it.month, locale),
                            values = listOf(it.collectedPaise, it.outstandingPaise),
                        )
                    },
                colors = listOf(moneyIn, moneyOut),
                legends = listOf(stringResource(R.string.reports_legend_collected), stringResource(R.string.reports_legend_outstanding)),
                axisFormatter = compactAmount,
                valueFormatter = money,
                modifier = modifier,
            )
        is ReportData.Aging -> {
            val labels = agingBucketLabels()
            SamarohBarChart(
                entries =
                    AgingBucket.entries.mapIndexed { index, bucket ->
                        ChartEntry(
                            label = labels[index],
                            fullLabel = labels[index],
                            values = listOf(data.bucketTotals[bucket] ?: 0L),
                        )
                    },
                colors = listOf(moneyOut),
                legends = listOf(stringResource(R.string.reports_legend_outstanding)),
                axisFormatter = compactAmount,
                valueFormatter = money,
                modifier = modifier,
            )
        }
        is ReportData.Occupancy ->
            SamarohBarChart(
                entries =
                    data.months.map {
                        ChartEntry(
                            label = monthAxisLabel(it.month, locale),
                            fullLabel = monthFullLabel(it.month, locale),
                            values = listOf(it.bookedDays.toLong()),
                        )
                    },
                colors = listOf(MaterialTheme.colorScheme.primary),
                legends = listOf(stringResource(R.string.reports_legend_booked_days)),
                axisFormatter = plainNumber,
                valueFormatter = plainNumber,
                modifier = modifier,
            )
        is ReportData.EventTypes ->
            SamarohPieChart(
                slices = data.rows.map { PieSlice(label = "${it.eventIcon} ${eventTypeLabel(it.eventType)}", value = it.revenuePaise) },
                colors = chartPalette(),
                valueFormatter = money,
                modifier = modifier,
            )
        is ReportData.Sources ->
            SamarohPieChart(
                slices = data.rows.map { PieSlice(label = sourceLabel(it.source), value = it.revenuePaise) },
                colors = chartPalette(),
                valueFormatter = money,
                modifier = modifier,
            )
        is ReportData.Expenses ->
            SamarohBarChart(
                entries =
                    data.months.map {
                        ChartEntry(
                            label = monthAxisLabel(it.month, locale),
                            fullLabel = monthFullLabel(it.month, locale),
                            values = listOf(it.ledgerPaise, it.inventoryPaise),
                        )
                    },
                colors = listOf(moneyOut, MaterialTheme.colorScheme.tertiary),
                legends =
                    listOf(
                        stringResource(R.string.reports_legend_spend),
                        stringResource(R.string.reports_expense_inventory_purchases_label),
                    ),
                axisFormatter = compactAmount,
                valueFormatter = money,
                modifier = modifier,
            )
        is ReportData.Profit ->
            SamarohLineChart(
                entries =
                    data.months.map {
                        ChartEntry(
                            label = monthAxisLabel(it.month, locale),
                            fullLabel = monthFullLabel(it.month, locale),
                            values = listOf(it.netPaise),
                        )
                    },
                legend = stringResource(R.string.reports_legend_net),
                axisFormatter = compactAmount,
                valueFormatter = money,
                modifier = modifier,
            )
        is ReportData.Inventory ->
            SamarohBarChart(
                entries =
                    data.rows.take(TOP_BARS).map {
                        ChartEntry(
                            label = it.name.take(BAR_LABEL_MAX_CHARS),
                            fullLabel = it.name,
                            values = listOf(it.valuePaise),
                        )
                    },
                colors = listOf(MaterialTheme.colorScheme.tertiary),
                legends = listOf(stringResource(R.string.reports_legend_value)),
                axisFormatter = compactAmount,
                valueFormatter = money,
                modifier = modifier,
            )
        is ReportData.PersonalExpenses -> {
            // Monthly totals across personal parties — bar per month, month order.
            val byMonth =
                data.rows
                    .groupBy { it.month }
                    .mapValues { (_, rows) -> rows.sumOf { it.netPaise } }
                    .toSortedMap()
            SamarohBarChart(
                entries =
                    byMonth.map { (month, netPaise) ->
                        ChartEntry(
                            label = monthAxisLabel(month, locale),
                            fullLabel = monthFullLabel(month, locale),
                            values = listOf(netPaise),
                        )
                    },
                colors = listOf(moneyOut),
                legends = listOf(stringResource(R.string.reports_legend_spend)),
                axisFormatter = compactAmount,
                valueFormatter = money,
                modifier = modifier,
            )
        }
        is ReportData.Collection -> {
            val result = data.result
            Column(modifier = modifier) {
                result.averageDays?.let { average ->
                    Text(
                        text = stringResource(R.string.reports_collection_average, CompactAmount.trimDecimal(average)),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                SamarohBarChart(
                    entries =
                        result.monthly.map {
                            ChartEntry(
                                label = monthAxisLabel(it.month, locale),
                                fullLabel = monthFullLabel(it.month, locale),
                                values = listOf(Math.round(it.averageDays)),
                            )
                        },
                    colors = listOf(MaterialTheme.colorScheme.primary),
                    legends = listOf(stringResource(R.string.reports_legend_avg_days)),
                    axisFormatter = plainNumber,
                    valueFormatter = plainNumber,
                )
            }
        }
    }
}
