package com.itsluminous.samaroh.feature.booking.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/**
 * ADR-073 + ADR-074 through the real posting path: every post lands on a v2 channel
 * whose sound is EXPLICIT (system default when the preference is unset), legacy
 * channels are retired, and only the full-screen styles set FLAG_INSISTENT.
 */
@RunWith(RobolectricTestRunner::class)
class BookingNotifierChannelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val notifier = BookingNotifier(context, FullScreenTakeover(context))

    private fun postedNotifications(): List<Notification> = shadowOf(manager).allNotifications

    private fun channel(id: String): NotificationChannel? = manager.getNotificationChannel(id)

    private fun paymentReminder(): PaymentReminder =
        PaymentReminder(
            id = "reminder-1",
            bookingId = "booking-1",
            businessId = Fixtures.BUSINESS_ID,
            remindOn = LocalDate.of(2026, 9, 9),
            amountDueSnapshotPaise = 50_000_00L,
            createdAt = Fixtures.NOW,
            updatedAt = Fixtures.NOW,
        )

    @Test
    fun `plain upcoming reminder - v2 base channel with the explicit system default sound, single play`() {
        notifier.postUpcomingReminder("booking-1", "title", 1)

        val posted = postedNotifications().single()
        assertThat(posted.channelId).isEqualTo("${BookingNotifier.CHANNEL_UPCOMING}_v2")
        assertThat(posted.flags and Notification.FLAG_INSISTENT).isEqualTo(0)
        assertThat(channel(posted.channelId)!!.sound).isEqualTo(Settings.System.DEFAULT_NOTIFICATION_URI)
    }

    @Test
    fun `full-screen upcoming reminder with UNSET sound - default-sound alarm channel, insistent`() {
        notifier.postFullScreenUpcomingReminder("booking-1", "title", 1, soundUri = null, style = ReminderStyle.FULLSCREEN)

        val defaultUri = Settings.System.DEFAULT_NOTIFICATION_URI.toString()
        val posted = postedNotifications().single()
        assertThat(posted.channelId)
            .isEqualTo(ReminderSoundPolicy.channelId(BookingNotifier.CHANNEL_UPCOMING, defaultUri))
        assertThat(posted.flags and Notification.FLAG_INSISTENT).isNotEqualTo(0)
        assertThat(channel(posted.channelId)!!.sound.toString()).isEqualTo(defaultUri)
    }

    @Test
    fun `full-screen upcoming reminder with a PICKED sound keeps it - existing users unaffected`() {
        val picked = "content://media/internal/audio/media/7"
        notifier.postFullScreenUpcomingReminder("booking-1", "title", 1, soundUri = picked, style = ReminderStyle.FULLSCREEN)

        val posted = postedNotifications().single()
        assertThat(posted.channelId).isEqualTo(ReminderSoundPolicy.channelId(BookingNotifier.CHANNEL_UPCOMING, picked))
        assertThat(channel(posted.channelId)!!.sound.toString()).isEqualTo(picked)
    }

    @Test
    fun `payment reminder - notification style single play, fullscreen style insistent`() {
        val booking = Fixtures.booking(id = "booking-1")
        notifier.postPaymentReminder(paymentReminder(), booking, "wedding", 1_00_000_00L, ReminderStyle.NOTIFICATION, soundUri = null)
        val plain = postedNotifications().single()
        assertThat(plain.flags and Notification.FLAG_INSISTENT).isEqualTo(0)
        assertThat(plain.channelId).isEqualTo("${BookingNotifier.CHANNEL_PAYMENT}_v2")

        notifier.postPaymentReminder(paymentReminder(), booking, "wedding", 1_00_000_00L, ReminderStyle.FULLSCREEN, soundUri = null)
        val fullScreen = postedNotifications().last()
        assertThat(fullScreen.flags and Notification.FLAG_INSISTENT).isNotEqualTo(0)
        assertThat(fullScreen.channelId)
            .isEqualTo(
                ReminderSoundPolicy.channelId(
                    BookingNotifier.CHANNEL_PAYMENT,
                    Settings.System.DEFAULT_NOTIFICATION_URI.toString(),
                ),
            )
    }

    @Test
    fun `posting retires the legacy pre-v2 channels`() {
        manager.createNotificationChannel(
            NotificationChannel(BookingNotifier.CHANNEL_UPCOMING, "legacy", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel("${BookingNotifier.CHANNEL_PAYMENT}_42", "legacy sound", NotificationManager.IMPORTANCE_HIGH),
        )

        notifier.postUpcomingReminder("booking-1", "title", 1)

        val ids = manager.notificationChannels.map { it.id }
        assertThat(ids).doesNotContain(BookingNotifier.CHANNEL_UPCOMING)
        assertThat(ids).doesNotContain("${BookingNotifier.CHANNEL_PAYMENT}_42")
        assertThat(ids).contains("${BookingNotifier.CHANNEL_UPCOMING}_v2")
    }
}
