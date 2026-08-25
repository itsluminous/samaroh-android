package com.itsluminous.samaroh.feature.reports

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.itsluminous.samaroh.feature.reports.domain.ReportType
import com.itsluminous.samaroh.feature.reports.ui.detail.ReportDetailScreen
import com.itsluminous.samaroh.feature.reports.ui.detail.ReportDetailViewModel
import com.itsluminous.samaroh.feature.reports.ui.home.ReportsHomeScreen

/** Route of the Reports section's start destination (reached from the Menu tab). */
const val REPORTS_ROUTE = "reports"

/**
 * Reports feature graph (§4.4): the nine-report home plus one detail screen per report,
 * each with a date-range filter, hand-rolled charts and PDF/CSV export. Everything is
 * gated behind the `reports.view` permission (§3). Sub-navigation is self-contained in a
 * nested NavHost so the app shell keeps calling `reportsGraph()` unchanged.
 */
fun NavGraphBuilder.reportsGraph() {
    composable(REPORTS_ROUTE) {
        ReportsHost()
    }
}

private object ReportsRoutes {
    const val HOME = "reports_home"
    const val DETAIL = "reports_detail/{${ReportDetailViewModel.REPORT_TYPE_ARG}}"

    fun detail(type: ReportType) = "reports_detail/${type.routeArg}"
}

@Composable
private fun ReportsHost() {
    val navController = rememberNavController()
    // The home screen's back arrow must pop the OUTER graph (back to the Menu tab), which
    // this nested host cannot reach — dispatching a system back does it without :app wiring.
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    NavHost(navController = navController, startDestination = ReportsRoutes.HOME) {
        composable(ReportsRoutes.HOME) {
            ReportsHomeScreen(
                onBack = { backDispatcher?.onBackPressed() },
                onOpenReport = { type -> navController.navigate(ReportsRoutes.detail(type)) },
            )
        }
        composable(
            route = ReportsRoutes.DETAIL,
            arguments = listOf(navArgument(ReportDetailViewModel.REPORT_TYPE_ARG) { type = NavType.StringType }),
        ) {
            ReportDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}
