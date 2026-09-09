package com.itsluminous.samaroh.feature.reports

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

private const val REPORT_ARG = "report"

/**
 * Route PATTERN of the Reports section (reached from the Menu tab). The optional
 * `report` query arg deep-links straight to one report's detail (ADR-075: Menu-search
 * results) — build concrete routes with [reportsRoute], never navigate to the pattern.
 */
const val REPORTS_ROUTE = "reports?report={$REPORT_ARG}"

/**
 * Concrete Reports route: the home when [reportArg] is null, or a deep link to that
 * report's detail. [reportArg] is a [ReportType.routeArg] wire value; anything unknown
 * lands on the tolerant [ReportType.fromRoute] default.
 */
fun reportsRoute(reportArg: String? = null): String = if (reportArg == null) "reports" else "reports?report=$reportArg"

/**
 * Reports feature graph (§4.4): the ten-report home plus one detail screen per report,
 * each with a date-range filter, hand-rolled charts and PDF/CSV export. Everything is
 * gated behind the `reports.view` permission (§3). Sub-navigation is self-contained in a
 * nested NavHost so the app shell keeps calling `reportsGraph()` unchanged.
 */
fun NavGraphBuilder.reportsGraph() {
    composable(
        route = REPORTS_ROUTE,
        arguments =
            listOf(
                navArgument(REPORT_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { entry ->
        ReportsHost(initialReportArg = entry.arguments?.getString(REPORT_ARG))
    }
}

private object ReportsRoutes {
    const val HOME = "reports_home"
    const val DETAIL = "reports_detail/{${ReportDetailViewModel.REPORT_TYPE_ARG}}"

    fun detail(type: ReportType) = "reports_detail/${type.routeArg}"
}

@Composable
private fun ReportsHost(initialReportArg: String?) {
    val navController = rememberNavController()
    // The home screen's back arrow must pop the OUTER graph (back to the Menu tab), which
    // this nested host cannot reach — dispatching a system back does it without :app wiring.
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // Menu-search deep link (ADR-075): push the requested detail over Home once per
    // host instance, so back from the report returns to the reports home.
    LaunchedEffect(initialReportArg) {
        if (initialReportArg != null) {
            navController.navigate(ReportsRoutes.detail(ReportType.fromRoute(initialReportArg))) {
                launchSingleTop = true
            }
        }
    }
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
