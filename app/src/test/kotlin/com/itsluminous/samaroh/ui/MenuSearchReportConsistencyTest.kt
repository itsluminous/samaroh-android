package com.itsluminous.samaroh.ui

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.feature.menu.ui.search.MenuSearchIndex
import com.itsluminous.samaroh.feature.reports.domain.ReportType
import com.itsluminous.samaroh.feature.reports.reportsRoute
import org.junit.Test

/**
 * ADR-075 cross-module pin: feature:menu's search index carries report route args as
 * STRINGS (feature modules never depend on each other), so the app — which owns both
 * graphs — asserts they stay identical to feature:reports' [ReportType] wire values,
 * and that the deep-link route builder round-trips them.
 */
class MenuSearchReportConsistencyTest {
    @Test
    fun `menu search report args exactly match the report types`() {
        assertThat(MenuSearchIndex.REPORT_ROUTE_ARGS)
            .containsExactlyElementsIn(ReportType.entries.map { it.routeArg })
    }

    @Test
    fun `every indexed report arg deep-links to its own report type`() {
        MenuSearchIndex.REPORT_ROUTE_ARGS.forEach { arg ->
            // The tolerant decoder must resolve the arg to the SAME report, not the fallback.
            assertThat(ReportType.fromRoute(arg).routeArg).isEqualTo(arg)
            assertThat(reportsRoute(arg)).isEqualTo("reports?report=$arg")
        }
    }

    @Test
    fun `reports home route stays the plain route`() {
        assertThat(reportsRoute()).isEqualTo("reports")
    }
}
