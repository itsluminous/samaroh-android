package com.itsluminous.samaroh.feature.reports.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.reports.domain.AgingBucket
import com.itsluminous.samaroh.feature.reports.domain.ReportTotals
import com.itsluminous.samaroh.feature.reports.export.CsvValues
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
 *
 * Money tables carry a final TOTAL row (ADR-027), and every table carries machine-
 * readable `csvRows` — plain decimal-rupee amounts and ISO dates — so spreadsheet apps
 * parse the CSV as numbers/dates while the screen and PDF keep localized formatting.
 */
@Composable
fun rememberReportTable(state: ReportDetailUiState): ReportTable? {
    val data = state.data ?: return null
    val locale = currentLocale()
    val title = stringResource(state.type.titleRes())
    val totalLabel = stringResource(R.string.reports_table_total_row)
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
        is ReportData.Revenue -> {
            val total = ReportTotals.revenue(data.months)
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
                totalRow = listOf(totalLabel, money(total.collectedPaise), money(total.outstandingPaise), money(total.totalPaise)),
                csvRows =
                    data.months.map {
                        listOf(
                            CsvValues.month(it.month),
                            CsvValues.rupees(it.collectedPaise),
                            CsvValues.rupees(it.outstandingPaise),
                            CsvValues.rupees(it.totalPaise),
                        )
                    },
                csvTotalRow =
                    listOf(
                        totalLabel,
                        CsvValues.rupees(total.collectedPaise),
                        CsvValues.rupees(total.outstandingPaise),
                        CsvValues.rupees(total.totalPaise),
                    ),
            )
        }
        is ReportData.Aging -> {
            val totalDue = ReportTotals.agingDuePaise(data.entries)
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
                totalRow = listOf(totalLabel, "", "", "", money(totalDue)),
                csvRows =
                    data.entries.map {
                        listOf(
                            it.booking.customerName,
                            eventTypeLabel(it.booking.eventType),
                            CsvValues.date(it.booking.endDate),
                            CsvValues.count(it.daysOverdue),
                            CsvValues.rupees(it.duePaise),
                        )
                    },
                csvTotalRow = listOf(totalLabel, "", "", "", CsvValues.rupees(totalDue)),
            )
        }
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
                csvRows =
                    data.months.map {
                        listOf(CsvValues.month(it.month), CsvValues.count(it.bookedDays), CsvValues.count(it.utilizationPercent))
                    },
            )
        is ReportData.EventTypes -> {
            val total = ReportTotals.eventTypes(data.rows)
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
                totalRow = listOf(totalLabel, total.bookings.toString(), money(total.revenuePaise)),
                csvRows =
                    data.rows.map {
                        listOf(eventTypeLabel(it.eventType), CsvValues.count(it.bookings), CsvValues.rupees(it.revenuePaise))
                    },
                csvTotalRow = listOf(totalLabel, CsvValues.count(total.bookings), CsvValues.rupees(total.revenuePaise)),
            )
        }
        is ReportData.Sources -> {
            val total = ReportTotals.sources(data.rows)
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
                totalRow = listOf(totalLabel, total.bookings.toString(), money(total.revenuePaise)),
                csvRows =
                    data.rows.map {
                        listOf(sourceLabel(it.source), CsvValues.count(it.bookings), CsvValues.rupees(it.revenuePaise))
                    },
                csvTotalRow = listOf(totalLabel, CsvValues.count(total.bookings), CsvValues.rupees(total.revenuePaise)),
            )
        }
        is ReportData.Expenses -> {
            val inventoryLabel = stringResource(R.string.reports_expense_inventory_purchases_label)
            val total = ReportTotals.expenses(data.months)
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
                totalRow = listOf(totalLabel, money(total.ledgerPaise), money(total.inventoryPaise), money(total.totalPaise)),
                csvRows =
                    data.months.map {
                        listOf(
                            CsvValues.month(it.month),
                            CsvValues.rupees(it.ledgerPaise),
                            CsvValues.rupees(it.inventoryPaise),
                            CsvValues.rupees(it.totalPaise),
                        )
                    },
                csvTotalRow =
                    listOf(
                        totalLabel,
                        CsvValues.rupees(total.ledgerPaise),
                        CsvValues.rupees(total.inventoryPaise),
                        CsvValues.rupees(total.totalPaise),
                    ),
            )
        }
        is ReportData.Profit -> {
            val total = ReportTotals.profit(data.months)
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
                totalRow = listOf(totalLabel, money(total.incomePaise), money(total.expensePaise), money(total.netPaise)),
                csvRows =
                    data.months.map {
                        listOf(
                            CsvValues.month(it.month),
                            CsvValues.rupees(it.incomePaise),
                            CsvValues.rupees(it.expensePaise),
                            CsvValues.rupees(it.netPaise),
                        )
                    },
                csvTotalRow =
                    listOf(
                        totalLabel,
                        CsvValues.rupees(total.incomePaise),
                        CsvValues.rupees(total.expensePaise),
                        CsvValues.rupees(total.netPaise),
                    ),
            )
        }
        is ReportData.Inventory -> {
            val totalValue = ReportTotals.valuationPaise(data.rows)
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
                // Quantities have mixed units, so only the value column totals.
                totalRow = listOf(totalLabel, "", money(totalValue)),
                csvRows =
                    data.rows.map {
                        listOf(
                            it.name,
                            stringResource(R.string.reports_format_quantity_unit, formatQuantity(it.quantity), it.unit),
                            CsvValues.rupees(it.valuePaise),
                        )
                    },
                csvTotalRow = listOf(totalLabel, "", CsvValues.rupees(totalValue)),
            )
        }
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
                csvRows =
                    data.result.entries.map {
                        listOf(
                            it.booking.customerName,
                            eventTypeLabel(it.booking.eventType),
                            CsvValues.date(it.booking.endDate),
                            CsvValues.date(it.fullyPaidOn),
                            CsvValues.count(it.daysToFullPayment),
                        )
                    },
            )
        is ReportData.PersonalExpenses -> {
            val unknownParty = stringResource(R.string.reports_expense_unknown_party)
            val total = ReportTotals.personalExpensesPaise(data.rows)
            ReportTable(
                title = title,
                subtitle = subtitle,
                columns =
                    listOf(
                        stringResource(R.string.reports_table_month),
                        stringResource(R.string.reports_table_party),
                        stringResource(R.string.reports_table_spend),
                    ),
                rows =
                    data.rows.map {
                        listOf(monthFullLabel(it.month, locale), displayPartyName(it.partyName, unknownParty), money(it.netPaise))
                    },
                columnWeights = listOf(1f, 1.6f, 1f),
                totalRow = listOf(totalLabel, "", money(total)),
                csvRows =
                    data.rows.map {
                        listOf(CsvValues.month(it.month), displayPartyName(it.partyName, unknownParty), CsvValues.rupees(it.netPaise))
                    },
                csvTotalRow = listOf(totalLabel, "", CsvValues.rupees(total)),
            )
        }
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
        totalRow =
            listOf(
                stringResource(R.string.reports_table_total_row),
                AmountFormatter.format(ReportTotals.partySpendPaise(data.top10)),
            ),
    )
}

/** Aging bucket labels in bucket order, localized — shared by the chart and the summary row. */
@Composable
fun agingBucketLabels(): List<String> = AgingBucket.entries.map { stringResource(it.labelRes()) }
