package com.itsluminous.samaroh.feature.booking.reminders

import android.app.NotificationManager

/**
 * Repeat-until-acknowledged policy for booking reminders (ADR-074).
 *
 * Both full-screen styles alert REPEATEDLY until the user acts; the plain
 * NOTIFICATION style keeps its single play:
 * - The posted notification carries `FLAG_INSISTENT`, so its sound loops until the
 *   notification is dismissed or opened — this covers the heads-up-banner and
 *   demoted-to-plain presentations where [FullScreenReminderActivity] never shows.
 * - When the popup activity IS shown it takes over as THE alert: it cancels the
 *   paired notification (silencing the insistent loop) and plays its own looping
 *   sound until Dismiss/View — or until the popup leaves the screen, which counts
 *   as acknowledging it.
 */
object ReminderRepeatPolicy {
    /** Whether the posted notification should loop its sound until acknowledged. */
    fun insistent(style: ReminderStyle): Boolean = style != ReminderStyle.NOTIFICATION

    /**
     * Whether the full-screen popup may play its looping sound: only while Do Not
     * Disturb is fully off. Any active interruption filter (priority-only,
     * alarms-only, total silence) keeps the popup visual-only — the user asked the
     * OS for quiet, and the notification path already gets muted the same way.
     */
    fun shouldLoopInPopup(interruptionFilter: Int): Boolean = interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
}
