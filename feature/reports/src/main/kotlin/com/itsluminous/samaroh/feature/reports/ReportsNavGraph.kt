package com.itsluminous.samaroh.feature.reports

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.samaroh.core.designsystem.component.PlaceholderScreen
import com.itsluminous.samaroh.core.i18n.R

/** Route of the Reports section's start destination (reached from the Menu tab). */
const val REPORTS_ROUTE = "reports"

/**
 * Reports feature graph (Wave 0 skeleton — W2-A implements the report set with
 * hand-rolled Compose charts and PDF/CSV export).
 */
fun NavGraphBuilder.reportsGraph() {
    composable(REPORTS_ROUTE) {
        PlaceholderScreen(featureNameRes = R.string.app_feature_reports, icon = Icons.Filled.BarChart)
    }
}
