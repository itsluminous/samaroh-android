package com.itsluminous.samaroh.ui

import com.itsluminous.samaroh.core.data.navigation.HelpDeepLinkIds
import com.itsluminous.samaroh.feature.booking.BOOKING_ROUTE
import com.itsluminous.samaroh.feature.expenses.EXPENSES_ROUTE
import com.itsluminous.samaroh.feature.files.FILES_ROUTE
import com.itsluminous.samaroh.feature.inventory.INVENTORY_ROUTE
import com.itsluminous.samaroh.feature.notes.NOTES_ROUTE

/**
 * Where a Help "Show me" deep link lands in the shell (ADR-108). The shell switches to the
 * owning tab / Menu; the inner landing is either done by the feature host (which reads the
 * id as a `State` and consumes it) or by the shell itself.
 */
sealed interface HelpLanding {
    /**
     * A bottom-bar tab. [featureLands] = the feature host navigates its inner graph and
     * consumes the target; false = the tab itself is the destination (the shell consumes).
     */
    data class Tab(
        val route: String,
        val featureLands: Boolean,
    ) : HelpLanding

    /** The Booking tab with the nearest booking's card open (ADR-098 holder; shell consumes). */
    data object BookingCard : HelpLanding

    /** A Menu-graph screen: the Menu host lands and consumes. */
    data object Menu : HelpLanding

    /** Reports (root-level over the Menu); the shell navigates and consumes. */
    data object Reports : HelpLanding

    /** The onboarding sign-in step over the current screen (ADR-089); the shell consumes. */
    data object SignIn : HelpLanding
}

/**
 * `routes.json` id → [HelpLanding]. Exhaustive over [HelpDeepLinkIds.all] (tested: every id
 * in the shared `routes.json` resolves, so a new guide route cannot ship unhandled).
 */
object HelpDeepLinkResolver {
    fun resolve(deepLinkId: String): HelpLanding? =
        when (deepLinkId) {
            HelpDeepLinkIds.BOOKING_CARD -> HelpLanding.BookingCard
            in HelpDeepLinkIds.booking -> HelpLanding.Tab(BOOKING_ROUTE, featureLands = true)
            in HelpDeepLinkIds.expenses -> HelpLanding.Tab(EXPENSES_ROUTE, featureLands = true)
            in HelpDeepLinkIds.inventory -> HelpLanding.Tab(INVENTORY_ROUTE, featureLands = true)
            HelpDeepLinkIds.NOTES_HOME -> HelpLanding.Tab(NOTES_ROUTE, featureLands = false)
            HelpDeepLinkIds.FILES_HOME -> HelpLanding.Tab(FILES_ROUTE, featureLands = false)
            in HelpDeepLinkIds.menu -> HelpLanding.Menu
            HelpDeepLinkIds.REPORTS_HOME, HelpDeepLinkIds.REPORTS_DETAIL -> HelpLanding.Reports
            HelpDeepLinkIds.AUTH_SIGN_IN -> HelpLanding.SignIn
            else -> null
        }
}
