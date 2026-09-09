package com.itsluminous.samaroh.feature.booking.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-073: an unset sound preference resolves to the SYSTEM DEFAULT notification
 * sound (never silence), channel ids carry the v2 generation, and pre-v2 channels
 * are recognized as legacy and deleted — other features' channels never match.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderSoundPolicyTest {
    @Test
    fun `unset preference resolves to the system default notification sound`() {
        assertThat(ReminderSoundPolicy.effectiveSoundUri(null))
            .isEqualTo(Settings.System.DEFAULT_NOTIFICATION_URI)
    }

    @Test
    fun `explicit pick resolves to itself`() {
        assertThat(ReminderSoundPolicy.effectiveSoundUri("content://media/ringtone/7").toString())
            .isEqualTo("content://media/ringtone/7")
    }

    @Test
    fun `base channel id carries the v2 generation`() {
        assertThat(ReminderSoundPolicy.channelId("booking_upcoming_reminders", null))
            .isEqualTo("booking_upcoming_reminders_v2")
    }

    @Test
    fun `per-sound channel id carries the generation and the sound hash`() {
        val uri = "content://media/ringtone/7"
        assertThat(ReminderSoundPolicy.channelId("booking_upcoming_reminders", uri))
            .isEqualTo("booking_upcoming_reminders_v2_${uri.hashCode()}")
    }

    @Test
    fun `legacy detection - old base and old per-sound ids are legacy, v2 ids are not`() {
        val base = "booking_payment_reminders"
        assertThat(ReminderSoundPolicy.isLegacy(base, base)).isTrue()
        assertThat(ReminderSoundPolicy.isLegacy(base, "${base}_12345")).isTrue()
        assertThat(ReminderSoundPolicy.isLegacy(base, "${base}_v2")).isFalse()
        assertThat(ReminderSoundPolicy.isLegacy(base, "${base}_v2_12345")).isFalse()
        // Other features' channels never match.
        assertThat(ReminderSoundPolicy.isLegacy(base, "sync_conflicts")).isFalse()
    }

    @Test
    fun `deleteLegacyChannels removes only retired reminder channels`() {
        val manager =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(NotificationManager::class.java)
        listOf(
            "booking_payment_reminders", // legacy base
            "booking_upcoming_reminders_99", // legacy per-sound
            "booking_upcoming_reminders_v2", // current generation
            "other_feature_channel", // untouched
        ).forEach { id ->
            manager.createNotificationChannel(
                NotificationChannel(id, id, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        ReminderSoundPolicy.deleteLegacyChannels(
            manager,
            BookingNotifier.CHANNEL_PAYMENT,
            BookingNotifier.CHANNEL_UPCOMING,
        )

        val remaining = manager.notificationChannels.map { it.id }
        assertThat(remaining).containsExactly("booking_upcoming_reminders_v2", "other_feature_channel")
    }
}
