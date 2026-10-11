package com.itsluminous.samaroh.e2e

import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.MainActivity
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.booking.reminders.BookingDeepLink
import com.itsluminous.samaroh.feature.booking.reminders.BookingDeepLinks
import com.itsluminous.samaroh.feature.booking.reminders.BookingNotifier
import com.itsluminous.samaroh.feature.booking.reminders.ReminderLedgerKey
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * ADR-098: tapping a reminder opens THAT booking's card — through the real notification
 * (the notifier posts it, the test fires its content intent exactly like a shade tap,
 * warm `onNewIntent` path), through a cold launch with the same intent, and gracefully
 * for a booking deleted since the reminder fired.
 */
abstract class ReminderDeepLinkTest(
    localeTag: String,
) : LocalizedE2eTest(localeTag) {
    @Inject lateinit var notifier: BookingNotifier

    private lateinit var business: Business
    private lateinit var booking: Booking

    /** Next month's 15th — never in the initially shown month, so the landing is observable. */
    private val bookingDate: LocalDate = YearMonth.now().plusMonths(1).atDay(15)

    override suspend fun seed() {
        business = seedOnboardedBusiness()
        booking = Fixtures.booking(startDate = bookingDate, totalAmountPaise = 2_00_000_00L).copy(customerName = "Meera")
        bookingRepository.saveBooking(booking)
    }

    private fun ledgerKey() = ReminderLedgerKey.Upcoming(booking.id, daysAway = 1, startDate = bookingDate)

    private fun monthHeader(date: LocalDate): String = YearMonth.from(date).format(DateTimeFormatter.ofPattern("MMMM yyyy", testLocale))

    private fun assertCardOpenForMeera() {
        waitForText(string(R.string.booking_card_payments_title))
        waitForText("Meera", substring = true)
    }

    @Test
    fun warm_notificationTapOpensBookingCard() {
        // Land on the calendar first (warm app), then post the reminder like the engine does.
        waitForText(monthHeader(LocalDate.now()))
        notifier.postUpcomingReminder(booking.id, "Meera", 1, ledgerKey(), business.id)

        val app = ApplicationProvider.getApplicationContext<Context>()
        val posted =
            app
                .getSystemService(NotificationManager::class.java)
                .activeNotifications
                .first { it.tag == BookingNotifier.UPCOMING_NOTIFICATION_TAG }
        // The content PendingIntent is what the shade fires on tap (singleTask → onNewIntent).
        posted.notification.contentIntent.send()

        assertCardOpenForMeera()
        waitForText(monthHeader(bookingDate))
    }

    @Test
    fun cold_launchWithReminderIntentOpensBookingCard() {
        scenario.close()
        val app = ApplicationProvider.getApplicationContext<Context>()
        val link = BookingDeepLink(booking.id, business.id, ledgerKey())

        scenario = ActivityScenario.launch<MainActivity>(BookingDeepLinks.launchIntent(app, link))

        assertCardOpenForMeera()
        waitForText(monthHeader(bookingDate))
    }

    @Test
    fun deletedBooking_landsOnItsDateWithoutCard() {
        waitForText(monthHeader(LocalDate.now()))
        runBlocking { bookingRepository.deleteBooking(booking.id) }
        val app = ApplicationProvider.getApplicationContext<Context>()

        BookingDeepLinks.pendingIntent(app, BookingDeepLink(booking.id, business.id, ledgerKey())).send()

        waitForText(monthHeader(bookingDate))
        compose.waitForIdle()
        // No card: the booking is gone, only its month is shown.
        assertThat(
            compose
                .onAllNodesWithText(string(R.string.booking_card_payments_title), useUnmergedTree = true)
                .fetchSemanticsNodes(),
        ).isEmpty()
    }
}

@HiltAndroidTest
class ReminderDeepLinkEnTest : ReminderDeepLinkTest("en")

@HiltAndroidTest
class ReminderDeepLinkHiTest : ReminderDeepLinkTest("hi")
