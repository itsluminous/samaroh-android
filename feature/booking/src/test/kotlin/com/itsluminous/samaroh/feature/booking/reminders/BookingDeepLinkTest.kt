package com.itsluminous.samaroh.feature.booking.reminders

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderKind
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/**
 * ADR-098: every reminder surface carries the SAME "open this booking" link — body tap,
 * the payment notification's Partial… action and the full-screen popup — and the
 * launch intent round-trips through `MainActivity`'s parser (cold and warm paths read
 * the identical extras).
 */
@RunWith(RobolectricTestRunner::class)
class BookingDeepLinkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val notifier = BookingNotifier(context, FullScreenTakeover(context))

    private val upcomingKey = ReminderLedgerKey.Upcoming("b-1", daysAway = 1, startDate = LocalDate.of(2026, 10, 12))
    private val paymentKey = ReminderLedgerKey.Row("r-1", "b-1", followUp = false)

    private fun PendingIntent.savedIntent(): Intent = shadowOf(this).savedIntent

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun reminder(kind: ReminderKind = ReminderKind.PAYMENT) =
        PaymentReminder(
            id = "r-1",
            bookingId = "b-1",
            businessId = Fixtures.BUSINESS_ID,
            remindOn = LocalDate.of(2026, 10, 11),
            amountDueSnapshotPaise = 50_000_00L,
            kind = kind,
            createdAt = Fixtures.NOW,
            updatedAt = Fixtures.NOW,
        )

    @Test
    fun `link round-trips through an intent`() {
        val link = BookingDeepLink("b-1", Fixtures.BUSINESS_ID, upcomingKey, BookingNotifier.UPCOMING_NOTIFICATION_TAG, 42)

        val decoded = BookingDeepLink.from(link.applyTo(Intent()))

        assertThat(decoded).isEqualTo(link)
    }

    @Test
    fun `legacy intent with only a booking id still decodes`() {
        val decoded = BookingDeepLink.from(Intent().putExtra(EXTRA_BOOKING_ID, "b-1"))

        assertThat(decoded).isEqualTo(BookingDeepLink("b-1"))
    }

    @Test
    fun `plain launcher start carries no link`() {
        assertThat(BookingDeepLink.from(null)).isNull()
        assertThat(BookingDeepLink.from(Intent())).isNull()
        assertThat(BookingDeepLink.from(Intent().putExtra(EXTRA_BOOKING_ID, ""))).isNull()
    }

    @Test
    fun `request codes differ per ledger key of the same booking`() {
        val upcoming = BookingDeepLink("b-1", ledgerKey = upcomingKey)
        val payment = BookingDeepLink("b-1", ledgerKey = paymentKey)
        val keyless = BookingDeepLink("b-1")

        assertThat(setOf(upcoming.requestCode(), payment.requestCode(), keyless.requestCode())).hasSize(3)
        // Stable: re-posting the same delivery updates the same PendingIntent.
        assertThat(BookingDeepLink("b-1", ledgerKey = upcomingKey).requestCode()).isEqualTo(upcoming.requestCode())
    }

    @Test
    fun `launch intent is the package launcher with CLEAR_TOP and the link extras`() {
        val link = BookingDeepLink("b-1", Fixtures.BUSINESS_ID, upcomingKey)

        val launch = BookingDeepLinks.launchIntent(context, link)

        assertThat(launch.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
        assertThat(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(BookingDeepLink.from(launch)).isEqualTo(link)
    }

    @Test
    fun `upcoming notification tap opens the booking with business + key`() {
        notifier.postUpcomingReminder("b-1", "title", 1, upcomingKey, Fixtures.BUSINESS_ID)

        val link = BookingDeepLink.from(posted().single().contentIntent.savedIntent())

        assertThat(link).isEqualTo(
            BookingDeepLink("b-1", Fixtures.BUSINESS_ID, upcomingKey, BookingNotifier.UPCOMING_NOTIFICATION_TAG, "b-1".hashCode()),
        )
    }

    @Test
    fun `full-screen upcoming notification and its popup carry the same booking + business`() {
        notifier.postFullScreenUpcomingReminder(
            "b-1",
            "title",
            1,
            soundUri = null,
            style = ReminderStyle.FULLSCREEN,
            ledgerKey = upcomingKey,
            businessId = Fixtures.BUSINESS_ID,
        )

        val notification = posted().single()
        val tap = BookingDeepLink.from(notification.contentIntent.savedIntent())
        val popup = notification.fullScreenIntent.savedIntent()

        assertThat(tap?.bookingId).isEqualTo("b-1")
        assertThat(tap?.businessId).isEqualTo(Fixtures.BUSINESS_ID)
        assertThat(tap?.ledgerKey).isEqualTo(upcomingKey)
        assertThat(popup.getStringExtra(EXTRA_BOOKING_ID)).isEqualTo("b-1")
        assertThat(popup.getStringExtra(EXTRA_BUSINESS_ID)).isEqualTo(Fixtures.BUSINESS_ID)
    }

    @Test
    fun `payment notification - body tap AND Partial action open the booking card`() {
        val booking = Fixtures.booking(id = "b-1", startDate = LocalDate.of(2026, 10, 1))
        notifier.postPaymentReminder(
            reminder(),
            booking,
            eventLabel = "Wedding",
            duePaise = 50_000_00L,
            style = ReminderStyle.NOTIFICATION,
            soundUri = null,
            ledgerKey = paymentKey,
        )

        val notification = posted().single()
        val expected =
            BookingDeepLink("b-1", Fixtures.BUSINESS_ID, paymentKey, BookingNotifier.PAYMENT_NOTIFICATION_TAG, "r-1".hashCode())
        assertThat(BookingDeepLink.from(notification.contentIntent.savedIntent())).isEqualTo(expected)
        // Actions: Yes-full (broadcast), Partial… (open), Not-yet (broadcast).
        assertThat(notification.actions).hasLength(3)
        assertThat(BookingDeepLink.from(notification.actions[1].actionIntent.savedIntent())).isEqualTo(expected)
    }

    @Test
    fun `follow-up notification tap opens the booking card with its row key`() {
        val booking = Fixtures.booking(id = "b-1", startDate = LocalDate.of(2026, 10, 20))
        val key = ReminderLedgerKey.Row("r-1", "b-1", followUp = true)
        notifier.postFollowUpReminder(
            reminder(ReminderKind.FOLLOW_UP),
            booking,
            eventLabel = "Wedding",
            style = ReminderStyle.NOTIFICATION,
            soundUri = null,
            ledgerKey = key,
        )

        val link = BookingDeepLink.from(posted().single().contentIntent.savedIntent())

        assertThat(link?.bookingId).isEqualTo("b-1")
        assertThat(link?.businessId).isEqualTo(Fixtures.BUSINESS_ID)
        assertThat(link?.ledgerKey).isEqualTo(key)
    }

    @Test
    fun `two reminders for one booking keep distinct content intents`() {
        val booking = Fixtures.booking(id = "b-1", startDate = LocalDate.of(2026, 10, 12))
        notifier.postUpcomingReminder("b-1", "title", 1, upcomingKey, Fixtures.BUSINESS_ID)
        notifier.postPaymentReminder(reminder(), booking, "Wedding", 1L, ReminderStyle.NOTIFICATION, null, paymentKey)

        val keys = posted().map { BookingDeepLink.from(it.contentIntent.savedIntent())?.ledgerKey }

        // Without per-key request codes the second post would overwrite the first's extras.
        assertThat(keys).containsExactly(upcomingKey, paymentKey)
    }
}
