package com.itsluminous.samaroh.feature.reports.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.reports.domain.AgingBucket
import com.itsluminous.samaroh.feature.reports.export.ReportTable
import com.itsluminous.samaroh.feature.reports.ui.currentLocale
import com.itsluminous.samaroh.feature.reports.ui.dateLabel
import com.itsluminous.samaroh.feature.reports.ui.eventTypeLabel
import com.itsluminous.samaroh.feature.reports.ui.formatQuantity
import com.itsluminous.samaroh.feature.reports.ui.labelRes
import com.itsluminous.samaroh.feature.reports.ui.monthFullLabel
import com.itsluminous.samaroh.feature.reports.ui.sourceLabel
import com.itsluminous.samaroh.feature.reports.ui.titleRes

/**
 * Builds the fully localized [ReportTable] for the current report state — the single
 * table model behind the on-screen grid, the CSV and the PDF, so all three always agree.
 */
@Composable
fun rememberReportTable(state: ReportDetailUiState): ReportTable? {
    val data = state.data ?: return null
    val locale = currentLocale()
    val title = stringResource(state.type.titleRes())
    val subtitle =
        if (state.type.supportsDateRange) {
            stringResource(
                R.string.reports_range_summary,
                dateLabel(state.range.start, locale),
                dateLabel(state.range.end, locale),
            )
        } else {
            stringResource(R.string.reports_inventory_snapshot_note)
        }

    fun money(paise: Long): String = AmountFormatter.format(paise)

    return when (data) {
        is ReportData.Revenue ->
            ReportTable(
                title = title,
                subtitle = subtitle,
                columns =
                    listOf(
                        stringResource(R.string.reports_table_month),
                        stringResource(R.string.reports_table_collected),
                        stringResource(R.string.reports_table_outstanding),
                        stringResource(R.string.reports_table_total),
                    ),
                rows =
                    data.months.map {
                        listOf(monthFullLabel(it.month, locale), money(it.collectedPaise), money(it.outstandingPaise), money(it.totalPaise))
                    },
            )
        is ReportData.Aging ->
            ReportTable(
                title = title,
                subtitle = subtitle,
                columns =
                    listOf(
                        stringResource(R.string.reports_table_customer),
                        stringResource(R.string.reports_table_event),
                        stringResource(R.string.reports_table_end_date),
                        stringResource(R.string.reports_table_days_overdue),
                        stringResource(R.string.reports_table_due),
                    ),
                rows =
                    data.entries.map {
                        listOf(
                            it.booking.customerName,
                            eventTypeLabel(it.booking.eventType),
                            dateLabel(it.booking.endDate, locale),
                            it.daysOverdue.toString(),
                            money(it.duePaise),
                        )
                    },
                columnWeights = listOf(1.4f, 1.2f, 1.1f, 0.9f, 1f),
            )
        is ReportData.Occupancy ->
            ReportTable(
                title = title,
                subtitle = subtitle,
                columns =
                    listOf(
                        stringResource(R.string.reports_table_month),
                        stringResource(R.string.reports_table_booked_days),
                        stringResource(R.string.reports_table_utilization),
                    ),
                rows =
                    data.months.map {
                        listOf(
                            monthFullLabel(it.month, locale),
                            it.bookedDays.toString(),
                            stringResource(R.string.reports_format_percent, it.utilizationPercent),
                        )
                    },
            )
        is ReportData.EventTypes ->
            ReportTable(
                title = title,
                subtitle = subtitle,
                columns =
                    listOf(
                        stringResource(R.string.reports_table_event_type),
                        stringResource(R.string.reports_table_bookings),
                        stringResource(R.string.reports_table_revenue),
                    ),
                rows =
                    data.rows.map {
                        listOf("${it.eventIcon} ${eventTypeLabel(it.eventType)}", it.bookings.toString(), money(it.revenuePaise))
                    },
            )
        is ReportData.Sources ->
            ReportTable(
                title = title,
                subtitle = subtitle,
                columns =
                    listOf(
                        stringResource(R.string.reports_table_source),
                        stringResource(R.string.reports_table_bookings),
                        stringResource(R.string.reports_table_revenue),
                    ),
                rows = data.rows.map { listOf(sourceLabel(it.source), it.bookings.toString(), money(it.revenuePaise)) },
            )
        is ReportData.Expenses -> {
            val inventoryLabel = stringResource(R.string.reports_expense_inventory_purchases_label)
            ReportTable(
                title = title,
                subtitle = subtitle,
                columns =
                    listOf(
                        stringResource(R.string.reports_table_month),
                        stringResource(R.string.reports_table_expenses),
                        inventoryLabel,
                        stringResource(R.string.reports_table_total),
                    ),
                rows =
                    data.months.map {
                        listOf(monthFullLabel(it.month, locale), money(it.ledgerPaise), money(it.inventoryPaise), money(it.totalPaise))
                    },
            )
        }
        is ReportData.Profit ->
            ReportTable(
                title = title,
                subtitle = subtitle,
                columns =
                    listOf(
                        stringResource(R.string.reports_table_month),
                        stringResource(R.string.reports_table_income),
                        stringResource(R.string.reports_table_expenses),
                        stringResource(R.string.reports_table_net),
                    ),
                rows =
                    data.months.map {
                        listOf(monthFullLabel(it.month, locale), money(it.incomePaise), money(it.expensePaise), money(it.netPaise))
                    },
            )
        is ReportData.Inventory ->
            ReportTable(
                title = title,
                subtitle = subtitle,
                columns =
                    listOf(
                        stringResource(R.string.reports_table_item),
                        stringResource(R.string.reports_table_quantity),
                        stringResource(R.string.reports_table_value),
                    ),
                rows =
                    data.rows.map {
                        listOf(
                            it.name,
                            stringResource(R.string.reports_format_quantity_unit, formatQuantity(it.quantity), it.unit),
                            money(it.valuePaise),
                        )
                    },
                columnWeights = listOf(1.8f, 1f, 1f),
            )
        is ReportData.Collection ->
            ReportTable(
                title = title,
                subtitle = subtitle,
                columns =
                    listOf(
                        stringResource(R.string.reports_table_customer),
                        stringResource(R.string.reports_table_event),
                        stringResource(R.string.reports_table_end_date),
                        stringResource(R.string.reports_table_paid_on),
                        stringResource(R.string.reports_table_days_to_pay),
                    ),
                rows =
                    data.result.entries.map {
                        listOf(
                            it.booking.customerName,
                            eventTypeLabel(it.booking.eventType),
                            dateLabel(it.booking.endDate, locale),
                            dateLabel(it.fullyPaidOn, locale),
                            it.daysToFullPayment.toString(),
                        )
                    },
                columnWeights = listOf(1.4f, 1.2f, 1.1f, 1.1f, 0.8f),
            )
    }
}

/** Swaps the ViewModel's unknown-party sentinel for the localized label. */
internal fun displayPartyName(
    name: String,
    unknownLabel: String,
): String = if (name == ReportDetailViewModel.UNKNOWN_PARTY_SENTINEL) unknownLabel else name

/**
 * Secondary spend-by-party table of the Expense summary, rendered on screen below the
 * monthly table but excluded from CSV/PDF export — web-parity with `extraTable`.
 * Null for other reports or when there are no party rows.
 */
@Composable
fun rememberExpensePartiesTable(state: ReportDetailUiState): ReportTable? {
    val data = state.data as? ReportData.Expenses ?: return null
    if (data.rows.isEmpty()) return null
    val unknownParty = stringResource(R.string.reports_expense_unknown_party)
    return ReportTable(
        title = stringResource(R.string.reports_report_expense_summary_subtitle),
        subtitle = "",
        columns =
            listOf(
                stringResource(R.string.reports_table_party),
                stringResource(R.string.reports_table_spend),
            ),
        rows =
            data.top10.map {
                listOf(displayPartyName(it.partyName, unknownParty), AmountFormatter.format(it.spendPaise))
            },
        columnWeights = listOf(2f, 1f),
    )
}

/** Aging bucket labels in bucket order, localized — shared by the chart and the summary row. */
@Composable
fun agingBucketLabels(): List<String> = AgingBucket.entries.map { stringResource(it.labelRes()) }
