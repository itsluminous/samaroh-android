package com.itsluminous.samaroh.feature.menu.domain

import com.itsluminous.samaroh.feature.menu.data.ReminderStyle

/**
 * Pure permission-status logic for Settings → Booking reminders (ADR-043, spec §6).
 * Every permission is OPTIONAL — the app is fully usable when denied; these rows only
 * surface the state and offer a fix. Pure and unit-tested; the screen supplies the
 * actual system states (SDK level, `areNotificationsEnabled`, `canUseFullScreenIntent`,
 * `canScheduleExactAlarms`).
 */
object ReminderPermissionsStatus {
    /** Which system grant a status row is about. */
    enum class Row {
        /** POST_NOTIFICATIONS / channel-level enablement — every reminder path needs it. */
        NOTIFICATIONS,

        /** Android 14+ full-screen-intent grant — the full-screen styles need it. */
        FULL_SCREEN,

        /** Android 12+ exact-alarm grant — fires the alarm-style reminder on time. */
        EXACT_ALARM,

        /**
         * "Display over other apps" (SYSTEM_ALERT_WINDOW special access) — the ALWAYS
         * full-screen style needs it to take over an unlocked, in-use device (ADR-072).
         */
        OVERLAY,
    }

    data class RowState(
        val row: Row,
        val granted: Boolean,
    )

    /**
     * The status rows to render for the given system state. The notifications row always
     * shows (pre-33 it is an install-time grant, so it reads as allowed unless the user
     * blocked the app/channel in settings); the full-screen-intent and exact-alarm rows
     * appear while EITHER full-screen style is selected AND the device's API level
     * actually gates them; the overlay row appears only for the ALWAYS style (ADR-072),
     * which is the only style whose behaviour that grant changes — so picking it shows
     * BOTH grants it depends on.
     */
    fun rows(
        sdkInt: Int,
        notificationsEnabled: Boolean,
        style: ReminderStyle,
        canUseFullScreenIntent: Boolean,
        canScheduleExactAlarms: Boolean,
        canDrawOverlays: Boolean,
    ): List<RowState> =
        buildList {
            add(RowState(Row.NOTIFICATIONS, granted = notificationsEnabled))
            if (style == ReminderStyle.FULLSCREEN_ALWAYS) {
                add(RowState(Row.OVERLAY, granted = canDrawOverlays))
            }
            if (style == ReminderStyle.FULLSCREEN || style == ReminderStyle.FULLSCREEN_ALWAYS) {
                if (sdkInt >= 34) add(RowState(Row.FULL_SCREEN, granted = canUseFullScreenIntent))
                if (sdkInt >= 31) add(RowState(Row.EXACT_ALARM, granted = canScheduleExactAlarms))
            }
        }

    /**
     * Whether opening a reminder-relevant surface should fire the CONTEXTUAL
     * POST_NOTIFICATIONS request (spec §6: ask in context, never at launch). Only on
     * API 33+ (the runtime permission exists) and only while not already granted —
     * the system itself stops showing the dialog after repeated denials.
     */
    fun shouldRequestNotifications(
        sdkInt: Int,
        notificationsEnabled: Boolean,
    ): Boolean = sdkInt >= 33 && !notificationsEnabled

    /**
     * Whether the Test button must show the full-screen-intent fix-it prompt INSTEAD of
     * firing (ADR-045): a full-screen style is selected but the Android 14+
     * full-screen-intent grant is off, so the OS would silently demote the popup to a
     * plain notification — a test that quietly lies is worse than none. Below API 34
     * the grant does not exist and the test always fires. Checked AFTER
     * [blocksAlwaysFullScreenTest] for the ALWAYS style (the overlay grant is that
     * style's defining permission, so its prompt wins).
     */
    fun blocksFullScreenTest(
        sdkInt: Int,
        style: ReminderStyle,
        canUseFullScreenIntent: Boolean,
    ): Boolean =
        (style == ReminderStyle.FULLSCREEN || style == ReminderStyle.FULLSCREEN_ALWAYS) &&
            sdkInt >= 34 &&
            !canUseFullScreenIntent

    /**
     * Whether the Test button must show the OVERLAY fix-it prompt instead of firing
     * (ADR-072): the ALWAYS style is selected but "display over other apps" is off.
     * A test tapped from inside the app would still take over (the app is foreground —
     * its own launch exemption), which would LIE about what a real background reminder
     * does — so the test is gated on the grant the real path needs.
     */
    fun blocksAlwaysFullScreenTest(
        style: ReminderStyle,
        canDrawOverlays: Boolean,
    ): Boolean = style == ReminderStyle.FULLSCREEN_ALWAYS && !canDrawOverlays
}
