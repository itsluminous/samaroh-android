package com.itsluminous.samaroh.feature.reports.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.EmptyState
import com.itsluminous.samaroh.core.designsystem.component.PermissionGate
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.reports.domain.ReportType
import com.itsluminous.samaroh.feature.reports.ui.ReportsScreenScaffold
import com.itsluminous.samaroh.feature.reports.ui.subtitleRes
import com.itsluminous.samaroh.feature.reports.ui.titleRes

/** Reports home (§4.4): the nine reports, gated behind `reports.view`. */
@Composable
fun ReportsHomeScreen(
    onBack: () -> Unit,
    onOpenReport: (ReportType) -> Unit,
    viewModel: ReportsHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReportsScreenScaffold(titleRes = R.string.reports_home_title, onBack = onBack) {
        if (!state.loading) {
            PermissionGate(
                allowed = state.canView,
                deniedContent = { ReportsDeniedState() },
            ) {
                androidx.compose.foundation.layout.Column {
                    // ADR-039: reports.view_amounts off hides money reports entirely —
                    // only count/duration reports (occupancy, collection days) remain.
                    val visibleReports = ReportType.entries.filter { state.canViewAmounts || !it.requiresAmounts }
                    visibleReports.forEachIndexed { index, type ->
                        if (index > 0) HorizontalDivider()
                        ReportRow(type = type, onClick = { onOpenReport(type) })
                    }
                }
            }
        }
    }
}

/** Localized no-access state (§3 layer 2 — RLS stays the authoritative layer). */
@Composable
internal fun ReportsDeniedState(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Filled.Lock,
        title = stringResource(R.string.reports_permission_denied_title),
        message = stringResource(R.string.reports_permission_denied_message),
        modifier = modifier,
    )
}

private fun ReportType.icon(): ImageVector =
    when (this) {
        ReportType.REVENUE -> Icons.Filled.BarChart
        ReportType.DUES_AGING -> Icons.Filled.CurrencyRupee
        ReportType.OCCUPANCY -> Icons.Filled.EventAvailable
        ReportType.EVENT_TYPES -> Icons.Filled.Category
        ReportType.SOURCES -> Icons.Filled.PieChart
        ReportType.EXPENSE_SUMMARY -> Icons.AutoMirrored.Filled.ShowChart
        ReportType.PROFIT -> Icons.AutoMirrored.Filled.TrendingUp
        ReportType.INVENTORY_VALUATION -> Icons.Filled.Inventory2
        ReportType.COLLECTION -> Icons.Filled.Schedule
        ReportType.PERSONAL_EXPENSES -> Icons.Filled.Person
    }

@Composable
private fun ReportRow(
    type: ReportType,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(type.titleRes()), style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(stringResource(type.subtitleRes())) },
        leadingContent = { Icon(type.icon(), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
