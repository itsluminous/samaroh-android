package com.itsluminous.samaroh.feature.booking.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
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
        private val takeover: FullScreenTakeover,
    ) {
        companion object {
            const val CHANNEL_PAYMENT = "booking_payment_reminders"
            const val CHANNEL_UPCOMING = "booking_upcoming_reminders"
            const val PAYMENT_NOTIFICATION_TAG = "booking_payment"
            const val UPCOMING_NOTIFICATION_TAG = "booking_upcoming"
        }

        private fun canNotify(): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Channels are immutable after creation, so a user-chosen sound gets its own
         * channel id (standard workaround for per-setting sounds), and the ADR-073
         * default-sound change ships as the `_v2` generation: the base channel now
         * carries the SYSTEM DEFAULT notification sound EXPLICITLY (an unset
         * preference means "system default", never silence), and retired pre-v2
         * channels are deleted so system settings list only live ones. Payment and
         * follow-up reminders share the payment channel; [fullScreenSoundUri] (the
         * RESOLVED sound, never null for a full-screen post) only exists for the
         * full-screen (alarm) styles, which carry the user-picked ringtone (ADR-045).
         */
        private fun ensurePaymentChannel(fullScreenSoundUri: String? = null): String =
            ensureChannel(
                base = CHANNEL_PAYMENT,
                nameRes = R.string.booking_reminder_channel_payment,
                descriptionRes = R.string.booking_reminder_channel_payment_desc,
                fullScreenSoundUri = fullScreenSoundUri,
            )

        /** Upcoming-reminder channel — same v2 generation + sound rules as the payment one. */
        private fun ensureUpcomingChannel(fullScreenSoundUri: String?): String =
            ensureChannel(
                base = CHANNEL_UPCOMING,
                nameRes = R.string.booking_reminder_channel_upcoming,
                descriptionRes = R.string.booking_reminder_channel_upcoming_desc,
                fullScreenSoundUri = fullScreenSoundUri,
            )

        private fun ensureChannel(
            base: String,
            nameRes: Int,
            descriptionRes: Int,
            fullScreenSoundUri: String?,
        ): String {
            val id = ReminderSoundPolicy.channelId(base, fullScreenSoundUri)
            val manager = context.getSystemService(NotificationManager::class.java) ?: return id
            ReminderSoundPolicy.deleteLegacyChannels(manager, CHANNEL_PAYMENT, CHANNEL_UPCOMING)
            manager.createNotificationChannel(
                NotificationChannel(
                    id,
                    context.getString(nameRes),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(descriptionRes)
                    if (fullScreenSoundUri != null) {
                        // Full-screen (alarm) styles: the resolved sound on the alarm stream.
                        setSound(
                            Uri.parse(fullScreenSoundUri),
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .build(),
                        )
                    } else {
                        // Plain notifications: the system default, explicit (ADR-073).
                        setSound(
                            ReminderSoundPolicy.effectiveSoundUri(null),
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
         * use, and silently demotes it when the Android 14+ grant is off. Full-screen
         * styles repeat their sound until acknowledged (ADR-074).
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
            val path = takeover.pathFor(style)
            val fullScreen = path != FullScreenLaunchPolicy.LaunchPath.NOTIFICATION_ONLY
            val fullScreenSound =
                if (fullScreen) ReminderSoundPolicy.effectiveSoundUri(soundUri).toString() else null
            val channel = ensurePaymentChannel(fullScreenSoundUri = fullScreenSound)

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

            val popupIntent =
                FullScreenReminderActivity.intent(
                    context,
                    booking.id,
                    context.getString(R.string.booking_reminder_payment_title),
                    question,
                    soundUri = fullScreenSound,
                    notificationTag = PAYMENT_NOTIFICATION_TAG,
                    notificationId = reminder.id.hashCode(),
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
                        popupIntent = popupIntent,
                        requestCode = reminder.id.hashCode(),
                    ).build()
                    .applyInsistent(ReminderRepeatPolicy.insistent(style))

            NotificationManagerCompat.from(context).notify(PAYMENT_NOTIFICATION_TAG, reminder.id.hashCode(), notification)
            if (path == FullScreenLaunchPolicy.LaunchPath.DIRECT_ACTIVITY) {
                // ALWAYS style (ADR-072): take over even on an unlocked, in-use device.
                takeover.launch(popupIntent)
            }
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
            val path = takeover.pathFor(style)
            val fullScreen = path != FullScreenLaunchPolicy.LaunchPath.NOTIFICATION_ONLY
            val fullScreenSound =
                if (fullScreen) ReminderSoundPolicy.effectiveSoundUri(soundUri).toString() else null
            val channel = ensurePaymentChannel(fullScreenSoundUri = fullScreenSound)
            val question =
                context.getString(
                    R.string.booking_reminder_follow_up_question,
                    booking.customerName,
                    eventLabel,
                )
            val popupIntent =
                FullScreenReminderActivity.intent(
                    context,
                    booking.id,
                    context.getString(R.string.booking_reminder_follow_up_title),
                    question,
                    soundUri = fullScreenSound,
                    notificationTag = PAYMENT_NOTIFICATION_TAG,
                    notificationId = reminder.id.hashCode(),
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
                        popupIntent = popupIntent,
                        requestCode = reminder.id.hashCode(),
                    ).build()
                    .applyInsistent(ReminderRepeatPolicy.insistent(style))
            NotificationManagerCompat.from(context).notify(PAYMENT_NOTIFICATION_TAG, reminder.id.hashCode(), notification)
            if (path == FullScreenLaunchPolicy.LaunchPath.DIRECT_ACTIVITY) {
                takeover.launch(popupIntent)
            }
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
                    .Builder(context, ensureUpcomingChannel(fullScreenSoundUri = null))
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
         * by the exact-alarm receiver when a full-screen style is selected (§4.1). With
         * the ALWAYS style (ADR-072) and an available launch exemption (overlay grant or
         * app foreground), the popup activity is ALSO started directly so it takes over
         * even on an unlocked, in-use device; the notification still posts for sound,
         * the shade record and the locked-screen takeover (singleInstance dedups).
         * The sound repeats until acknowledged (ADR-074): the notification loops via
         * FLAG_INSISTENT, and the popup — when shown — takes the loop over itself.
         */
        @SuppressLint("MissingPermission") // guarded by canNotify()
        fun postFullScreenUpcomingReminder(
            bookingId: String,
            title: String,
            daysAway: Int,
            soundUri: String?,
            style: ReminderStyle = ReminderStyle.FULLSCREEN,
        ) {
            if (!canNotify()) return
            val effectiveSound = ReminderSoundPolicy.effectiveSoundUri(soundUri).toString()
            val activityIntent =
                FullScreenReminderActivity.intent(
                    context,
                    bookingId,
                    title,
                    daysAway,
                    soundUri = effectiveSound,
                    notificationTag = UPCOMING_NOTIFICATION_TAG,
                    notificationId = bookingId.hashCode(),
                )
            val fullScreenIntent =
                PendingIntent.getActivity(
                    context,
                    bookingId.hashCode(),
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(context, ensureUpcomingChannel(effectiveSound))
                    .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                    .setContentTitle(title)
                    .setContentText(daysAwayText(daysAway))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setFullScreenIntent(fullScreenIntent, true)
                    .build()
                    .applyInsistent(ReminderRepeatPolicy.insistent(style))
            NotificationManagerCompat.from(context).notify(UPCOMING_NOTIFICATION_TAG, bookingId.hashCode(), notification)
            if (takeover.pathFor(style) == FullScreenLaunchPolicy.LaunchPath.DIRECT_ACTIVITY) {
                takeover.launch(activityIntent)
            }
        }

        fun daysAwayText(daysAway: Int): String =
            context.resources.getQuantityString(R.plurals.booking_reminder_upcoming_days, daysAway, daysAway)

        /**
         * Adds the alarm-style full-screen treatment to a reminder notification
         * (ADR-045): a full-screen intent launching [FullScreenReminderActivity] via
         * [popupIntent], MAX priority and the ALARM category. The OS decides what
         * actually happens: full takeover on a locked/off screen, a heads-up banner
         * while the device is in use, and a silent demotion to a plain notification
         * when the Android 14+ full-screen-intent grant is off.
         */
        private fun NotificationCompat.Builder.applyFullScreenStyle(
            enabled: Boolean,
            popupIntent: Intent,
            requestCode: Int,
        ): NotificationCompat.Builder {
            if (!enabled) return this
            val fullScreenIntent =
                PendingIntent.getActivity(
                    context,
                    requestCode,
                    popupIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            return setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreenIntent, true)
        }

        /**
         * Repeat-until-acknowledged (ADR-074): loops the channel sound until the
         * notification is dismissed or opened. Applied for the full-screen styles only.
         */
        private fun Notification.applyInsistent(enabled: Boolean): Notification =
            apply { if (enabled) flags = flags or Notification.FLAG_INSISTENT }

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
