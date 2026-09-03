package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import com.itsluminous.samaroh.core.data.reminders.ReminderTestFirer
import com.itsluminous.samaroh.core.i18n.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires the SAMPLE reminder behind the Settings Test button (ADR-045) through the
 * EXACT production pipeline:
 * - Simple style → [BookingNotifier.postUpcomingReminder], the very call the daily
 *   pass makes — same channel, same formatting.
 * - Full-screen style → [UpcomingReminderAlarmReceiver.scheduleExactAt], so the sample
 *   travels AlarmManager → receiver → full-screen-intent notification with the chosen
 *   sound, identical to a real 09:00 popup. The short delay both exercises the real
 *   alarm path and gives the owner a moment to lock the screen and see the true
 *   take-over behavior (screen on = heads-up banner, by Android design).
 */
@Singleton
class BookingReminderTestFirer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val notifier: BookingNotifier,
        private val prefs: BookingReminderPrefs,
        private val clock: Clock,
    ) : ReminderTestFirer {
        override suspend fun fireSample() {
            val settings = prefs.current()
            val title = context.getString(R.string.booking_reminder_test_sample_title)
            when (settings.style) {
                ReminderStyle.NOTIFICATION ->
                    notifier.postUpcomingReminder(SAMPLE_BOOKING_ID, title, SAMPLE_DAYS_AWAY)
                ReminderStyle.FULLSCREEN ->
                    UpcomingReminderAlarmReceiver.scheduleExactAt(
                        context = context,
                        bookingId = SAMPLE_BOOKING_ID,
                        title = title,
                        daysAway = SAMPLE_DAYS_AWAY,
                        soundUri = settings.soundUri,
                        triggerAtMillis = clock.millis() + FULLSCREEN_FIRE_DELAY_MS,
                    )
            }
        }

        companion object {
            /** Stable fake id: re-tests replace the previous sample instead of stacking. */
            const val SAMPLE_BOOKING_ID = "reminder-style-test"
            const val SAMPLE_DAYS_AWAY = 1

            /** Long enough to lock the screen and watch the popup take over; still "immediate". */
            const val FULLSCREEN_FIRE_DELAY_MS = 3_000L
        }
    }
