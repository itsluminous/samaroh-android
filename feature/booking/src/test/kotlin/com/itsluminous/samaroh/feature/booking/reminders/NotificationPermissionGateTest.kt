package com.itsluminous.samaroh.feature.booking.reminders

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Form-open POST_NOTIFICATIONS gating (ADR-043 plumbing, ADR-044 placement): prompt
 * exactly while a system dialog can still actually appear — never re-nag a permanently
 * denied user, never prompt below API 33, never prompt once notifications are enabled.
 */
class NotificationPermissionGateTest {
    @Test
    fun `never asked before - prompts`() {
        assertThat(
            NotificationPermissionGate.shouldPrompt(
                sdkInt = 34,
                notificationsEnabled = false,
                requestedBefore = false,
                shouldShowRationale = false,
            ),
        ).isTrue()
    }

    @Test
    fun `denied once - system still shows the dialog - prompts`() {
        assertThat(
            NotificationPermissionGate.shouldPrompt(
                sdkInt = 34,
                notificationsEnabled = false,
                requestedBefore = true,
                shouldShowRationale = true,
            ),
        ).isTrue()
    }

    @Test
    fun `permanently denied - requested before and no rationale - stays silent`() {
        assertThat(
            NotificationPermissionGate.shouldPrompt(
                sdkInt = 34,
                notificationsEnabled = false,
                requestedBefore = true,
                shouldShowRationale = false,
            ),
        ).isFalse()
    }

    @Test
    fun `notifications already enabled - no prompt`() {
        assertThat(
            NotificationPermissionGate.shouldPrompt(
                sdkInt = 34,
                notificationsEnabled = true,
                requestedBefore = false,
                shouldShowRationale = false,
            ),
        ).isFalse()
    }

    @Test
    fun `below API 33 - no runtime permission exists - no prompt`() {
        assertThat(
            NotificationPermissionGate.shouldPrompt(
                sdkInt = 32,
                notificationsEnabled = false,
                requestedBefore = false,
                shouldShowRationale = false,
            ),
        ).isFalse()
    }
}
