package com.itsluminous.samaroh.di

import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.feature.booking.reminders.BookingDeepLink
import com.itsluminous.samaroh.feature.booking.reminders.BookingDeepLinkHolder
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Help "Show me" for `booking/card` (ADR-108): the guide cannot know WHICH booking, so the
 * shell picks the one the owner most likely means — the nearest upcoming live booking
 * (today or later; soonest first), else the most recent past one — and routes it onto the
 * ADR-098 `BookingDeepLinkHolder`, so the landing is exactly a reminder tap's: the
 * booking's month with its card open. No booking at all → the calendar only.
 */
@Singleton
class HelpBookingCardOpener
    @Inject
    constructor(
        private val bookingRepository: BookingRepository,
        private val activeBusinessProvider: ActiveBusinessProvider,
        private val deepLinks: BookingDeepLinkHolder,
        private val clock: Clock,
    ) {
        /** Parks the nearest booking's link; returns false when the business has none. */
        suspend fun openNearestCard(): Boolean {
            val business = activeBusinessProvider.activeBusiness.first() ?: return false
            val today = LocalDate.now(clock)
            val candidates =
                bookingRepository.bookingsBetween(business.id, today.minusYears(1), today.plusYears(1)).first()
            val booking = nearestBooking(candidates, today) ?: return false
            deepLinks.set(BookingDeepLink(bookingId = booking.id, businessId = business.id))
            return true
        }

        companion object {
            /**
             * The booking a "show me the card" should open: the soonest live booking starting
             * today or later; else the most recently started live booking; cancelled ones only
             * when nothing else exists.
             */
            fun nearestBooking(
                bookings: List<Booking>,
                today: LocalDate,
            ): Booking? {
                val live = bookings.filter { it.status != BookingStatus.CANCELLED }
                val pool = live.ifEmpty { bookings }
                return pool.filter { !it.startDate.isBefore(today) }.minByOrNull { it.startDate }
                    ?: pool.maxByOrNull { it.startDate }
            }
        }
    }
