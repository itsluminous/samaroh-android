package com.itsluminous.samaroh.feature.booking.reminders

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.feature.booking.reminders.FullScreenLaunchPolicy.LaunchPath
import org.junit.Test

/**
 * Launch-path selection per style + grant (ADR-072): the ALWAYS style starts the popup
 * activity directly only when a background-activity-launch exemption exists (the
 * "display over other apps" grant, or the app itself foreground) — otherwise it
 * degrades to the full-screen-intent notification instead of a launch the OS would
 * silently discard.
 */
class FullScreenLaunchPolicyTest {
    @Test
    fun `notification style never involves the full-screen machinery`() {
        for (overlay in listOf(true, false)) {
            for (foreground in listOf(true, false)) {
                assertThat(FullScreenLaunchPolicy.pathFor(ReminderStyle.NOTIFICATION, overlay, foreground))
                    .isEqualTo(LaunchPath.NOTIFICATION_ONLY)
            }
        }
    }

    @Test
    fun `locked-only style uses the full-screen notification regardless of grants`() {
        for (overlay in listOf(true, false)) {
            for (foreground in listOf(true, false)) {
                assertThat(FullScreenLaunchPolicy.pathFor(ReminderStyle.FULLSCREEN, overlay, foreground))
                    .isEqualTo(LaunchPath.FULL_SCREEN_NOTIFICATION)
            }
        }
    }

    @Test
    fun `always style launches directly with the overlay grant`() {
        assertThat(
            FullScreenLaunchPolicy.pathFor(ReminderStyle.FULLSCREEN_ALWAYS, overlayGranted = true, appInForeground = false),
        ).isEqualTo(LaunchPath.DIRECT_ACTIVITY)
    }

    @Test
    fun `always style launches directly while the app is foreground even without the grant`() {
        assertThat(
            FullScreenLaunchPolicy.pathFor(ReminderStyle.FULLSCREEN_ALWAYS, overlayGranted = false, appInForeground = true),
        ).isEqualTo(LaunchPath.DIRECT_ACTIVITY)
    }

    @Test
    fun `always style without any exemption degrades to the locked-only behaviour`() {
        // A direct start would be silently discarded by the OS — never a launch that
        // quietly goes nowhere.
        assertThat(
            FullScreenLaunchPolicy.pathFor(ReminderStyle.FULLSCREEN_ALWAYS, overlayGranted = false, appInForeground = false),
        ).isEqualTo(LaunchPath.FULL_SCREEN_NOTIFICATION)
    }
}
