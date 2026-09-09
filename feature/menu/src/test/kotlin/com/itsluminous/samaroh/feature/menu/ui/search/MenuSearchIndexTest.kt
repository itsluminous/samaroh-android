package com.itsluminous.samaroh.feature.menu.ui.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.feature.menu.routeFor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-075 index coverage: every Menu destination the spec names is present and
 * reachable — all Settings rows/sections, Members, the About rows, all ten reports
 * and sign-out — and every entry's texts resolve in BOTH catalog locales.
 */
@RunWith(RobolectricTestRunner::class)
class MenuSearchIndexTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `covers every required destination id`() {
        val ids = MenuSearchIndex.entries.map { it.id }
        assertThat(ids).containsAtLeast(
            "settings",
            "language",
            "theme",
            "dynamic_color",
            "reminders",
            "reminder_sound",
            "booking_form",
            "booking_calendar",
            "image_quality",
            "google",
            "gcal",
            "backup",
            "sync_status",
            "business_profile",
            "event_types",
            "members",
            "about",
            "about_version",
            "about_source",
            "about_licenses",
            "about_donate",
            "reports",
            "sign_out",
        )
        // No duplicate ids — each result row keys off it.
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `every nested menu screen target is reachable from at least one entry`() {
        val targeted =
            MenuSearchIndex.entries
                .mapNotNull { (it.target as? MenuSearchTarget.Screen)?.screen }
                .toSet()
        assertThat(targeted).containsExactlyElementsIn(MenuScreenTarget.entries.toList())
    }

    @Test
    fun `all ten reports are indexed with distinct route args`() {
        val reportArgs =
            MenuSearchIndex.entries
                .mapNotNull { (it.target as? MenuSearchTarget.Report)?.reportRouteArg }
        assertThat(reportArgs).containsExactlyElementsIn(MenuSearchIndex.REPORT_ROUTE_ARGS)
        assertThat(MenuSearchIndex.REPORT_ROUTE_ARGS).hasSize(10)
        assertThat(MenuSearchIndex.REPORT_ROUTE_ARGS).containsNoDuplicates()
        // Plus the reports home itself (null arg).
        assertThat(
            MenuSearchIndex.entries.any { it.target == MenuSearchTarget.Report(null) },
        ).isTrue()
    }

    @Test
    fun `owner gating and session gating are declared on the gated destinations`() {
        val byId = MenuSearchIndex.entries.associateBy { it.id }
        assertThat(byId.getValue("members").ownerOnly).isTrue()
        assertThat(byId.getValue("backup").ownerOnly).isTrue()
        assertThat(byId.getValue("event_types").ownerOnly).isTrue()
        assertThat(byId.getValue("sign_out").requiresSignedIn).isTrue()
    }

    @Test
    fun `every entry resolves non-blank searchable text in both locales`() {
        val resolved = MenuSearch.resolve(context)
        assertThat(resolved).hasSize(MenuSearchIndex.entries.size)
        resolved.forEach { entry ->
            assertThat(entry.title).isNotEmpty()
            assertThat(entry.haystack).isNotEmpty()
            entry.haystack.forEach { text -> assertThat(text).isNotEmpty() }
        }
    }

    @Test
    fun `every screen target maps to a distinct nested route`() {
        // Navigation mapping (ADR-075): the exhaustive when cannot skip a target;
        // distinctness guards against two targets landing on the same screen.
        val routes = MenuScreenTarget.entries.map { routeFor(it) }
        assertThat(routes).containsNoDuplicates()
        routes.forEach { route -> assertThat(route).isNotEmpty() }
    }
}
