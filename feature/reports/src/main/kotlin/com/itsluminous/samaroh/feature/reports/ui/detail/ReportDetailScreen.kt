package com.itsluminous.samaroh.feature.reports.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.PermissionGate
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.reports.domain.RangePreset
import com.itsluminous.samaroh.feature.reports.domain.ReportType
import com.itsluminous.samaroh.feature.reports.export.ReportExportFormat
import com.itsluminous.samaroh.feature.reports.export.ReportTable
import com.itsluminous.samaroh.feature.reports.share.ReportShare
import com.itsluminous.samaroh.feature.reports.ui.ReportsScreenScaffold
import com.itsluminous.samaroh.feature.reports.ui.currentLocale
import com.itsluminous.samaroh.feature.reports.ui.dateLabel
import com.itsluminous.samaroh.feature.reports.ui.home.ReportsDeniedState
import com.itsluminous.samaroh.feature.reports.ui.titleRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** One report (§4.4): date-range filter, hand-rolled chart, data table, PDF/CSV export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailScreen(
    onBack: () -> Unit,
    viewModel: ReportDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val shareRequest by viewModel.shareRequests.collectAsStateWithLifecycle()
    val exportFailed by viewModel.exportFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(shareRequest) {
        shareRequest?.let { exported ->
            ReportShare.share(context, exported)
            viewModel.onShared()
        }
    }

    var showRangePicker by rememberSaveable { mutableStateOf(false) }

    ReportsScreenScaffold(
        titleRes = state.type.titleRes(),
        onBack = onBack,
        messageRes = if (exportFailed) R.string.reports_export_failed else null,
        onMessageShown = viewModel::onExportFailureShown,
    ) {
        if (!state.loading) {
            PermissionGate(
                allowed = state.allowed,
                deniedContent = { ReportsDeniedState() },
            ) {
                Column {
                    if (state.type.supportsDateRange) {
                        RangeFilterRow(
                            state = state,
                            onPreset = viewModel::selectPreset,
                            onCustomClick = { showRangePicker = true },
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.reports_inventory_snapshot_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    val data = state.data
                    if (data == null || data.isEmpty) {
                        val personal = state.type == ReportType.PERSONAL_EXPENSES
                        EmptyState(
                            icon = Icons.Filled.BarChart,
                            title =
                                stringResource(
                                    if (personal) R.string.reports_personal_expenses_empty_title else R.string.reports_empty_title,
                                ),
                            message =
                                stringResource(
                                    if (personal) R.string.reports_personal_expenses_empty_message else R.string.reports_empty_message,
                                ),
                        )
                    } else {
                        ReportChart(data = data, modifier = Modifier.padding(top = 8.dp))
                        val table = rememberReportTable(state)
                        if (table != null) {
                            ExportButtonsRow(onExport = { format -> viewModel.export(table, format) })
                            ReportTableGrid(table = table)
                        }
                        val partiesTable = rememberExpensePartiesTable(state)
                        if (partiesTable != null) {
                            Text(
                                text = partiesTable.title,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            ReportTableGrid(table = partiesTable)
                        }
                    }
                }
            }
        }
    }

    if (showRangePicker) {
        ReportRangePickerDialog(
            onDismiss = { showRangePicker = false },
            onConfirm = { start, end ->
                showRangePicker = false
                viewModel.selectCustomRange(start, end)
            },
        )
    }
}

@Composable
private fun RangeFilterRow(
    state: ReportDetailUiState,
    onPreset: (RangePreset) -> Unit,
    onCustomClick: () -> Unit,
) {
    val locale = currentLocale()
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.preset == RangePreset.THIS_MONTH,
                onClick = { onPreset(RangePreset.THIS_MONTH) },
                label = { Text(stringResource(R.string.reports_range_this_month)) },
            )
            FilterChip(
                selected = state.preset == RangePreset.LAST_3_MONTHS,
                onClick = { onPreset(RangePreset.LAST_3_MONTHS) },
                label = { Text(stringResource(R.string.reports_range_last_3_months)) },
            )
            FilterChip(
                selected = state.preset == RangePreset.LAST_12_MONTHS,
                onClick = { onPreset(RangePreset.LAST_12_MONTHS) },
                label = { Text(stringResource(R.string.reports_range_last_12_months)) },
            )
            FilterChip(
                selected = state.preset == RangePreset.CUSTOM,
                onClick = onCustomClick,
                label = { Text(stringResource(R.string.reports_range_custom)) },
            )
        }
        Text(
            text =
                stringResource(
                    R.string.reports_range_summary,
                    dateLabel(state.range.start, locale),
                    dateLabel(state.range.end, locale),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportRangePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val pickerState = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val startMillis = pickerState.selectedStartDateMillis
                    val endMillis = pickerState.selectedEndDateMillis
                    if (startMillis != null && endMillis != null) {
                        onConfirm(millisToDate(startMillis), millisToDate(endMillis))
                    } else {
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    ) {
        DateRangePicker(
            state = pickerState,
            title = {
                Text(
                    text = stringResource(R.string.reports_range_picker_title),
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun millisToDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun ExportButtonsRow(onExport: (ReportExportFormat) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = { onExport(ReportExportFormat.PDF) }) {
            Text(stringResource(R.string.reports_export_pdf))
        }
        OutlinedButton(onClick = { onExport(ReportExportFormat.CSV) }) {
            Text(stringResource(R.string.reports_export_csv))
        }
    }
}

/** On-screen rendering of the export table: header row + data rows with column weights. */
@Composable
private fun ReportTableGrid(table: ReportTable) {
    val weights =
        if (table.columnWeights.size == table.columns.size && table.columnWeights.isNotEmpty()) {
            table.columnWeights
        } else {
            List(table.columns.size) { 1f }
        }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            table.columns.forEachIndexed { index, column ->
                Text(
                    text = column,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(weights[index]).padding(vertical = 4.dp),
                )
            }
        }
        HorizontalDivider()
        table.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { index, cell ->
                    Text(
                        text = cell,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(weights.getOrElse(index) { 1f }).padding(vertical = 4.dp),
                    )
                }
            }
        }
        table.totalRow?.let { totalRow ->
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth()) {
                totalRow.forEachIndexed { index, cell ->
                    Text(
                        text = cell,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(weights.getOrElse(index) { 1f }).padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}
