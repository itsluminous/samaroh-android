package com.itsluminous.samaroh.feature.booking.reminders

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure launch-path decision for the ALWAYS-full-screen reminder style (ADR-072).
 *
 * Android deliberately shows a full-screen-intent notification as a heads-up banner
 * while the device is on and unlocked — the activity takes over only on a locked/off
 * screen. The "Always full screen" style wants the takeover REGARDLESS of lock state,
 * which means starting [FullScreenReminderActivity] directly from the alarm receiver /
 * daily pass. Background activity launches are restricted (Android 10+), but two
 * exemptions apply here:
 * - the app holds the user-granted SYSTEM_ALERT_WINDOW ("display over other apps")
 *   grant, which exempts it from the background-activity-launch restriction, or
 * - the app is currently FOREGROUND (the user is inside it), where starting an
 *   activity is always allowed.
 *
 * Without either, the direct start would be silently discarded by the OS — so the
 * policy falls back to the full-screen-intent notification (the "when locked"
 * behaviour), never a launch that quietly goes nowhere.
 */
object FullScreenLaunchPolicy {
    /** How a full-screen-capable reminder should reach the user. */
    enum class LaunchPath {
        /** Plain notification only — the NOTIFICATION style. */
        NOTIFICATION_ONLY,

        /** Full-screen-intent notification: takeover when locked, banner otherwise. */
        FULL_SCREEN_NOTIFICATION,

        /**
         * Full-screen-intent notification PLUS a direct activity start, so the popup
         * takes over even on an unlocked, in-use device. The notification still posts
         * (sound + shade record + actions); the singleInstance activity dedups when
         * the OS launches the full-screen intent too.
         */
        DIRECT_ACTIVITY,
    }

    fun pathFor(
        style: ReminderStyle,
        overlayGranted: Boolean,
        appInForeground: Boolean,
    ): LaunchPath =
        when (style) {
            ReminderStyle.NOTIFICATION -> LaunchPath.NOTIFICATION_ONLY
            ReminderStyle.FULLSCREEN -> LaunchPath.FULL_SCREEN_NOTIFICATION
            ReminderStyle.FULLSCREEN_ALWAYS ->
                if (overlayGranted || appInForeground) {
                    LaunchPath.DIRECT_ACTIVITY
                } else {
                    // No exemption → the OS would discard the direct start; degrade to
                    // the "when locked" behaviour instead of silently doing nothing.
                    LaunchPath.FULL_SCREEN_NOTIFICATION
                }
        }
}

/**
 * Supplies the SYSTEM state behind [FullScreenLaunchPolicy] and performs the direct
 * activity start. Kept thin so the policy itself stays pure and unit-tested.
 */
@Singleton
class FullScreenTakeover
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** The user-granted "display over other apps" special-access grant. */
        fun overlayGranted(): Boolean = Settings.canDrawOverlays(context)

        /** Whether OUR process is currently foreground (activity visible to the user). */
        fun appInForeground(): Boolean {
            val state = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(state)
            return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

        fun pathFor(style: ReminderStyle): FullScreenLaunchPolicy.LaunchPath =
            FullScreenLaunchPolicy.pathFor(
                style = style,
                overlayGranted = overlayGranted(),
                appInForeground = appInForeground(),
            )

        /** Direct start of the reminder popup — only called on the DIRECT_ACTIVITY path. */
        fun launch(intent: Intent) {
            // Best-effort: if the OS still blocks the launch (vendor quirks), the
            // full-screen-intent notification posted alongside remains the fallback.
            runCatching { context.startActivity(intent) }
        }
    }
