package com.itsluminous.samaroh.feature.booking.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.PaymentReminder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Extra used on launch intents so W2 integration can deep-link to the booking. */
const val EXTRA_BOOKING_ID = "com.itsluminous.samaroh.booking.EXTRA_BOOKING_ID"

/**
 * Builds and posts the booking notifications (§4.1). Notifications are best-effort —
 * the in-app Pending-confirmations card is the reliable path; posting silently no-ops
 * when POST_NOTIFICATIONS is denied (§6: every permission optional).
 */
@Singleton
class BookingNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            const val CHANNEL_PAYMENT = "booking_payment_reminders"
            const val CHANNEL_UPCOMING = "booking_upcoming_reminders"
            private const val PAYMENT_NOTIFICATION_TAG = "booking_payment"
            private const val UPCOMING_NOTIFICATION_TAG = "booking_upcoming"
        }

        private fun canNotify(): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Channels are immutable after creation, so a user-chosen sound gets its own
         * channel id (standard workaround for per-setting sounds). Payment/follow-up
         * reminders share the payment channel; the sound variant only exists for the
         * full-screen (alarm) style, which carries the user-picked ringtone (ADR-045).
         */
        private fun ensurePaymentChannel(soundUri: String? = null): String {
            val id = if (soundUri == null) CHANNEL_PAYMENT else "${CHANNEL_PAYMENT}_${soundUri.hashCode()}"
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    id,
                    context.getString(R.string.booking_reminder_channel_payment),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.booking_reminder_channel_payment_desc)
                    if (soundUri != null) {
                        setSound(
                            Uri.parse(soundUri),
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .build(),
                        )
                    }
                },
            )
            return id
        }

        /**
         * Channels are immutable after creation, so a user-chosen sound gets its own
         * channel id (standard workaround for per-setting sounds).
         */
        private fun ensureUpcomingChannel(soundUri: String?): String {
            val id = if (soundUri == null) CHANNEL_UPCOMING else "${CHANNEL_UPCOMING}_${soundUri.hashCode()}"
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    id,
                    context.getString(R.string.booking_reminder_channel_upcoming),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.booking_reminder_channel_upcoming_desc)
                    if (soundUri != null) {
                        setSound(
                            Uri.parse(soundUri),
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .build(),
                        )
                    }
                },
            )
            return id
        }

        /**
         * "Did {customer} pay ₹{due} for {event}?" with Yes-full / Partial… / Not-yet
         * actions (§4.1). Honors the user-selected reminder style (ADR-045): with the
         * FULLSCREEN style the notification carries a full-screen intent (and the
         * chosen alarm sound), so it takes over a locked/off screen exactly like the
         * upcoming-event popup; the OS shows it as a heads-up while the device is in
         * use, and silently demotes it when the Android 14+ grant is off.
         */
        @SuppressLint("MissingPermission") // guarded by canNotify()
        fun postPaymentReminder(
            reminder: PaymentReminder,
            booking: Booking,
            eventLabel: String,
            duePaise: Long,
            style: ReminderStyle,
            soundUri: String?,
        ) {
            if (!canNotify()) return
            val fullScreen = style == ReminderStyle.FULLSCREEN
            val channel = ensurePaymentChannel(soundUri = soundUri.takeIf { fullScreen })

            val question =
                context.getString(
                    R.string.booking_reminder_payment_question,
                    booking.customerName,
                    AmountFormatter.format(duePaise),
                    eventLabel,
                )

            fun actionIntent(action: String): PendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    (reminder.id + action).hashCode(),
                    Intent(context, PaymentReminderActionReceiver::class.java).apply {
                        this.action = action
                        putExtra(PaymentReminderActionReceiver.EXTRA_REMINDER_ID, reminder.id)
                        putExtra(PaymentReminderActionReceiver.EXTRA_BOOKING_ID, booking.id)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat
                    .Builder(context, channel)
                    .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                    .setContentTitle(context.getString(R.string.booking_reminder_payment_title))
                    .setContentText(question)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(question))
                    .setContentIntent(launchAppIntent(booking.id))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true) // post-sync passes re-post; only a fresh notification alerts (ADR-024)
                    .addAction(
                        0,
                        context.getString(R.string.booking_reminder_action_yes_full),
                        actionIntent(PaymentReminderActionReceiver.ACTION_YES_FULL),
                    ).addAction(
                        0,
                        context.getString(R.string.booking_reminder_action_partial),
                        launchAppIntent(booking.id),
                    ).addAction(
                        0,
                        context.getString(R.string.booking_reminder_action_not_yet),
                        actionIntent(PaymentReminderActionReceiver.ACTION_NOT_YET),
                    ).applyFullScreenStyle(
                        enabled = fullScreen,
                        bookingId = booking.id,
                        title = context.getString(R.string.booking_reminder_payment_title),
                        body = question,
                        requestCode = reminder.id.hashCode(),
                    ).build()

            NotificationManagerCompat.from(context).notify(PAYMENT_NOTIFICATION_TAG, reminder.id.hashCode(), notification)
        }

        fun cancelPaymentReminder(reminderId: String) {
            NotificationManagerCompat.from(context).cancel(PAYMENT_NOTIFICATION_TAG, reminderId.hashCode())
        }

        /**
         * "Follow up with {customer} about {event}" for a tentative booking (ADR-020).
         * Tap opens the app on the booking; Confirm/Cancel/Snooze live on the in-app
         * follow-up card — the reliable path, like payment confirmations. Honors the
         * selected reminder style like every other reminder kind (ADR-045).
         */
        @SuppressLint("MissingPermission") // guarded by canNotify()
        fun postFollowUpReminder(
            reminder: PaymentReminder,
            booking: Booking,
            eventLabel: String,
            style: ReminderStyle,
            soundUri: String?,
        ) {
            if (!canNotify()) return
            val fullScreen = style == ReminderStyle.FULLSCREEN
            val channel = ensurePaymentChannel(soundUri = soundUri.takeIf { fullScreen })
            val question =
                context.getString(
                    R.string.booking_reminder_follow_up_question,
                    booking.customerName,
                    eventLabel,
                )
            val notification =
                NotificationCompat
                    .Builder(context, channel)
                    .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                    .setContentTitle(context.getString(R.string.booking_reminder_follow_up_title))
                    .setContentText(question)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(question))
                    .setContentIntent(launchAppIntent(booking.id))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true) // post-sync passes re-post; only a fresh notification alerts (ADR-024)
                    .applyFullScreenStyle(
                        enabled = fullScreen,
                        bookingId = booking.id,
                        title = context.getString(R.string.booking_reminder_follow_up_title),
                        body = question,
                        requestCode = reminder.id.hashCode(),
                    ).build()
            NotificationManagerCompat.from(context).notify(PAYMENT_NOTIFICATION_TAG, reminder.id.hashCode(), notification)
        }

        /** Simple upcoming-event notification: title line + "in {n} days" (§4.1). */
        @SuppressLint("MissingPermission") // guarded by canNotify()
        fun postUpcomingReminder(
            bookingId: String,
            title: String,
            daysAway: Int,
        ) {
            if (!canNotify()) return
            val notification =
                NotificationCompat
                    .Builder(context, ensureUpcomingChannel(soundUri = null))
                    .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                    .setContentTitle(title)
                    .setContentText(daysAwayText(daysAway))
                    .setContentIntent(launchAppIntent(bookingId))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true) // post-sync passes re-post; only a fresh notification alerts (ADR-024)
                    .build()
            NotificationManagerCompat.from(context).notify(UPCOMING_NOTIFICATION_TAG, bookingId.hashCode(), notification)
        }

        /**
         * Full-screen (alarm-style) upcoming reminder with the configured sound — posted
         * by the exact-alarm receiver when the "fullscreen" style is selected (§4.1).
         */
        @SuppressLint("MissingPermission") // guarded by canNotify()
        fun postFullScreenUpcomingReminder(
            bookingId: String,
            title: String,
            daysAway: Int,
            soundUri: String?,
        ) {
            if (!canNotify()) return
            val fullScreenIntent =
                PendingIntent.getActivity(
                    context,
                    bookingId.hashCode(),
                    FullScreenReminderActivity.intent(context, bookingId, title, daysAway),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(context, ensureUpcomingChannel(soundUri))
                    .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                    .setContentTitle(title)
                    .setContentText(daysAwayText(daysAway))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setFullScreenIntent(fullScreenIntent, true)
                    .build()
            NotificationManagerCompat.from(context).notify(UPCOMING_NOTIFICATION_TAG, bookingId.hashCode(), notification)
        }

        fun daysAwayText(daysAway: Int): String =
            context.resources.getQuantityString(R.plurals.booking_reminder_upcoming_days, daysAway, daysAway)

        /**
         * Adds the alarm-style full-screen treatment to a reminder notification
         * (ADR-045): a full-screen intent launching [FullScreenReminderActivity] with
         * the given title/body, MAX priority and the ALARM category. The OS decides
         * what actually happens: full takeover on a locked/off screen, a heads-up
         * banner while the device is in use, and a silent demotion to a plain
         * notification when the Android 14+ full-screen-intent grant is off.
         */
        private fun NotificationCompat.Builder.applyFullScreenStyle(
            enabled: Boolean,
            bookingId: String,
            title: String,
            body: String,
            requestCode: Int,
        ): NotificationCompat.Builder {
            if (!enabled) return this
            val fullScreenIntent =
                PendingIntent.getActivity(
                    context,
                    requestCode,
                    FullScreenReminderActivity.intent(context, bookingId, title, body),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            return setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreenIntent, true)
        }

        /** Opens the app (launcher activity) carrying the booking id for later deep-link wiring (W2). */
        private fun launchAppIntent(bookingId: String): PendingIntent {
            val launch =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    putExtra(EXTRA_BOOKING_ID, bookingId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                } ?: Intent()
            return PendingIntent.getActivity(
                context,
                bookingId.hashCode(),
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
