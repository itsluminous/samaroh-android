package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.booking.domain.BookingDeepLinkResolver.Landing
import org.junit.Test
import java.time.LocalDate

/** ADR-098: where a reminder deep link lands, including every graceful fallback. */
class BookingDeepLinkResolverTest {
    private val date = LocalDate.of(2026, 10, 12)
    private val booking = Fixtures.booking(id = "b-1", startDate = date)

    @Test
    fun `live booking of the active business opens its card on its date`() {
        val landing = BookingDeepLinkResolver.resolve(Fixtures.BUSINESS_ID, Fixtures.BUSINESS_ID, booking, canView = true)
        assertThat(landing).isEqualTo(Landing.Card("b-1", date))
    }

    @Test
    fun `cancelled booking still opens its card - exactly what the calendar does`() {
        val cancelled = booking.copy(status = BookingStatus.CANCELLED)
        val landing = BookingDeepLinkResolver.resolve(Fixtures.BUSINESS_ID, Fixtures.BUSINESS_ID, cancelled, canView = true)
        assertThat(landing).isEqualTo(Landing.Card("b-1", date))
    }

    @Test
    fun `deleted booking lands on its date with no card`() {
        val deleted = booking.copy(deletedAt = Fixtures.NOW)
        val landing = BookingDeepLinkResolver.resolve(Fixtures.BUSINESS_ID, Fixtures.BUSINESS_ID, deleted, canView = true)
        assertThat(landing).isEqualTo(Landing.Date(date))
    }

    @Test
    fun `unknown booking id lands on the calendar as-is`() {
        val landing = BookingDeepLinkResolver.resolve(Fixtures.BUSINESS_ID, Fixtures.BUSINESS_ID, booking = null, canView = true)
        assertThat(landing).isEqualTo(Landing.Calendar)
    }

    @Test
    fun `reminder for another business shows nothing - v1 has no business selection to switch`() {
        val landing = BookingDeepLinkResolver.resolve("other-business", Fixtures.BUSINESS_ID, booking, canView = true)
        assertThat(landing).isEqualTo(Landing.Calendar)
    }

    @Test
    fun `legacy intent without a business id falls back to the booking's business`() {
        val own = BookingDeepLinkResolver.resolve(null, Fixtures.BUSINESS_ID, booking, canView = true)
        assertThat(own).isEqualTo(Landing.Card("b-1", date))

        val foreign = BookingDeepLinkResolver.resolve(null, Fixtures.BUSINESS_ID, booking.copy(businessId = "other"), canView = true)
        assertThat(foreign).isEqualTo(Landing.Calendar)
    }

    @Test
    fun `no booking view permission shows nothing sensitive`() {
        val landing = BookingDeepLinkResolver.resolve(Fixtures.BUSINESS_ID, Fixtures.BUSINESS_ID, booking, canView = false)
        assertThat(landing).isEqualTo(Landing.Calendar)
    }

    @Test
    fun `no active business (pre-onboarding) shows nothing`() {
        val landing = BookingDeepLinkResolver.resolve(Fixtures.BUSINESS_ID, activeBusinessId = null, booking = booking, canView = true)
        assertThat(landing).isEqualTo(Landing.Calendar)
    }
}
