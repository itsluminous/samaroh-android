package com.itsluminous.samaroh.di

import com.itsluminous.samaroh.core.data.navigation.BookingCardOpener
import com.itsluminous.samaroh.feature.booking.reminders.BookingDeepLink
import com.itsluminous.samaroh.feature.booking.reminders.BookingDeepLinkHolder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-feature "open this booking's card" (ADR-101 §3): the Expenses ledger's event chip
 * calls the `core:data` seam; the app — the only module that sees both features — routes
 * it onto the ADR-098 reminder deep-link holder. The shell then switches to the Booking tab
 * and the calendar ViewModel lands on the month with the card open (deleted → the date,
 * foreign business / no `booking.view` → nothing), exactly like a reminder tap.
 */
@Singleton
class BookingDeepLinkCardOpener
    @Inject
    constructor(
        private val deepLinks: BookingDeepLinkHolder,
    ) : BookingCardOpener {
        override fun openBookingCard(
            bookingId: String,
            businessId: String,
        ) {
            deepLinks.set(BookingDeepLink(bookingId = bookingId, businessId = businessId))
        }
    }

/** App-only wiring between feature modules that must not depend on each other. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NavigationBridgeModule {
    @Binds
    abstract fun bindBookingCardOpener(impl: BookingDeepLinkCardOpener): BookingCardOpener
}
