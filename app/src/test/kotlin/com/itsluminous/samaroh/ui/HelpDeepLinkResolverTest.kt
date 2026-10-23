package com.itsluminous.samaroh.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.samaroh.core.data.navigation.HelpDeepLinkIds
import com.itsluminous.samaroh.core.help.HelpGuide
import com.itsluminous.samaroh.core.help.HelpRoutes
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.di.HelpBookingCardOpener
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Help "Show me" resolution (ADR-108): EVERY deepLink id in the shared `routes.json` — and
 * every id the guide actually uses — resolves to a landing, so a route added to the guide
 * cannot ship without the shell knowing where it goes (drift guard).
 */
class HelpDeepLinkResolverTest {
    private val helpDir = File(checkNotNull(System.getProperty("samaroh.sharedHelpDir")))
    private val routes = HelpRoutes.parse(File(helpDir, "routes.json").readText())
    private val guide = HelpGuide.parse(File(helpDir, "guide.json").readText())

    @Test
    fun `every routes json id resolves`() {
        for ((id, route) in routes.routes) {
            val landing = HelpDeepLinkResolver.resolve(id)
            assertWithMessage("deepLink '$id' (android route ${route.android}) has no landing").that(landing).isNotNull()
        }
        assertThat(HelpDeepLinkIds.all).containsExactlyElementsIn(routes.routes.keys)
    }

    @Test
    fun `every deepLink the guide uses resolves`() {
        val used = guide.screens.flatMap { s -> listOf(s.deepLink) + s.tasks.map { it.deepLink } }.toSet()
        for (id in used) {
            assertWithMessage("guide deepLink '$id'").that(HelpDeepLinkResolver.resolve(id)).isNotNull()
        }
    }

    @Test
    fun `landings pick the owning tab`() {
        assertThat(HelpDeepLinkResolver.resolve("booking/calendar")).isEqualTo(HelpLanding.Tab(BOOKING_ROUTE, featureLands = true))
        assertThat(HelpDeepLinkResolver.resolve("booking/form")).isEqualTo(HelpLanding.Tab(BOOKING_ROUTE, featureLands = true))
        assertThat(HelpDeepLinkResolver.resolve("booking/card")).isEqualTo(HelpLanding.BookingCard)
        assertThat(HelpDeepLinkResolver.resolve("expenses/add_party")).isEqualTo(HelpLanding.Tab(EXPENSES_ROUTE, featureLands = true))
        assertThat(HelpDeepLinkResolver.resolve("inventory/masterlist")).isEqualTo(HelpLanding.Tab(INVENTORY_ROUTE, featureLands = true))
        assertThat((HelpDeepLinkResolver.resolve("notes/home") as HelpLanding.Tab).featureLands).isFalse()
        assertThat((HelpDeepLinkResolver.resolve("files/home") as HelpLanding.Tab).featureLands).isFalse()
        assertThat(HelpDeepLinkResolver.resolve("menu/settings/reminders")).isEqualTo(HelpLanding.Menu)
        assertThat(HelpDeepLinkResolver.resolve("reports/detail")).isEqualTo(HelpLanding.Reports)
        assertThat(HelpDeepLinkResolver.resolve("auth/sign_in")).isEqualTo(HelpLanding.SignIn)
        assertThat(HelpDeepLinkResolver.resolve("nope/nothing")).isNull()
    }

    @Test
    fun `nearest booking prefers the soonest upcoming live booking, then the latest past one`() {
        val today = LocalDate.of(2026, 10, 23)
        val past = Fixtures.booking(startDate = today.minusDays(10))
        val recentPast = Fixtures.booking(startDate = today.minusDays(2))
        val soon = Fixtures.booking(startDate = today.plusDays(3))
        val later = Fixtures.booking(startDate = today.plusDays(30))
        val cancelledToday = Fixtures.booking(startDate = today).copy(status = BookingStatus.CANCELLED)

        assertThat(HelpBookingCardOpener.nearestBooking(listOf(later, past, soon, recentPast, cancelledToday), today)).isEqualTo(soon)
        assertThat(HelpBookingCardOpener.nearestBooking(listOf(past, recentPast, cancelledToday), today)).isEqualTo(recentPast)
        assertThat(HelpBookingCardOpener.nearestBooking(listOf(cancelledToday), today)).isEqualTo(cancelledToday)
        assertThat(HelpBookingCardOpener.nearestBooking(emptyList(), today)).isNull()
        // A booking starting today counts as upcoming.
        val todayLive = Fixtures.booking(startDate = today)
        assertThat(HelpBookingCardOpener.nearestBooking(listOf(soon, todayLive), today)).isEqualTo(todayLive)
    }
}
