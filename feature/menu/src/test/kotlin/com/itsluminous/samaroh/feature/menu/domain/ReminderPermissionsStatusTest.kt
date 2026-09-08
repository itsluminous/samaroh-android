package com.itsluminous.samaroh.feature.menu.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.feature.menu.data.ReminderStyle
import com.itsluminous.samaroh.feature.menu.domain.ReminderPermissionsStatus.Row
import org.junit.Test

/** Permission-status logic for Settings → Booking reminders (ADR-043, spec §6). */
class ReminderPermissionsStatusTest {
    @Test
    fun `notification style shows only the notifications row`() {
        val rows =
            ReminderPermissionsStatus.rows(
                sdkInt = 35,
                notificationsEnabled = true,
                style = ReminderStyle.NOTIFICATION,
                canUseFullScreenIntent = false,
                canScheduleExactAlarms = false,
                canDrawOverlays = false,
            )
        assertThat(rows.map { it.row }).containsExactly(Row.NOTIFICATIONS)
        assertThat(rows.single().granted).isTrue()
    }

    @Test
    fun `fullscreen style adds full-screen and exact-alarm rows on API 34+`() {
        val rows =
            ReminderPermissionsStatus.rows(
                sdkInt = 35,
                notificationsEnabled = false,
                style = ReminderStyle.FULLSCREEN,
                canUseFullScreenIntent = false,
                canScheduleExactAlarms = true,
                canDrawOverlays = false,
            )
        assertThat(rows.map { it.row }).containsExactly(Row.NOTIFICATIONS, Row.FULL_SCREEN, Row.EXACT_ALARM).inOrder()
        assertThat(rows.first { it.row == Row.NOTIFICATIONS }.granted).isFalse()
        assertThat(rows.first { it.row == Row.FULL_SCREEN }.granted).isFalse()
        assertThat(rows.first { it.row == Row.EXACT_ALARM }.granted).isTrue()
    }

    @Test
    fun `full-screen row hidden below API 34, exact-alarm row hidden below API 31`() {
        val api33 =
            ReminderPermissionsStatus.rows(
                sdkInt = 33,
                notificationsEnabled = true,
                style = ReminderStyle.FULLSCREEN,
                canUseFullScreenIntent = false,
                canScheduleExactAlarms = false,
                canDrawOverlays = false,
            )
        assertThat(api33.map { it.row }).containsExactly(Row.NOTIFICATIONS, Row.EXACT_ALARM).inOrder()

        val api30 =
            ReminderPermissionsStatus.rows(
                sdkInt = 30,
                notificationsEnabled = true,
                style = ReminderStyle.FULLSCREEN,
                canUseFullScreenIntent = false,
                canScheduleExactAlarms = false,
                canDrawOverlays = false,
            )
        assertThat(api30.map { it.row }).containsExactly(Row.NOTIFICATIONS)
    }

    @Test
    fun `contextual request fires only on API 33+ while not granted`() {
        assertThat(ReminderPermissionsStatus.shouldRequestNotifications(sdkInt = 35, notificationsEnabled = false)).isTrue()
        assertThat(ReminderPermissionsStatus.shouldRequestNotifications(sdkInt = 35, notificationsEnabled = true)).isFalse()
        // Pre-33 there is no runtime permission to request — the row still shows state.
        assertThat(ReminderPermissionsStatus.shouldRequestNotifications(sdkInt = 32, notificationsEnabled = false)).isFalse()
    }

    @Test
    fun `always-fullscreen style shows BOTH grants it depends on plus exact alarms`() {
        val rows =
            ReminderPermissionsStatus.rows(
                sdkInt = 35,
                notificationsEnabled = true,
                style = ReminderStyle.FULLSCREEN_ALWAYS,
                canUseFullScreenIntent = true,
                canScheduleExactAlarms = true,
                canDrawOverlays = false,
            )
        assertThat(rows.map { it.row })
            .containsExactly(Row.NOTIFICATIONS, Row.OVERLAY, Row.FULL_SCREEN, Row.EXACT_ALARM)
            .inOrder()
        assertThat(rows.first { it.row == Row.OVERLAY }.granted).isFalse()
        assertThat(rows.first { it.row == Row.FULL_SCREEN }.granted).isTrue()
    }

    @Test
    fun `overlay row appears only for the always-fullscreen style`() {
        for (style in listOf(ReminderStyle.NOTIFICATION, ReminderStyle.FULLSCREEN)) {
            val rows =
                ReminderPermissionsStatus.rows(
                    sdkInt = 35,
                    notificationsEnabled = true,
                    style = style,
                    canUseFullScreenIntent = true,
                    canScheduleExactAlarms = true,
                    canDrawOverlays = true,
                )
            assertThat(rows.map { it.row }).doesNotContain(Row.OVERLAY)
        }
    }

    @Test
    fun `overlay row has no SDK gate — the grant exists on every supported level`() {
        val rows =
            ReminderPermissionsStatus.rows(
                sdkInt = 26,
                notificationsEnabled = true,
                style = ReminderStyle.FULLSCREEN_ALWAYS,
                canUseFullScreenIntent = true,
                canScheduleExactAlarms = true,
                canDrawOverlays = true,
            )
        assertThat(rows.map { it.row }).containsExactly(Row.NOTIFICATIONS, Row.OVERLAY).inOrder()
        assertThat(rows.first { it.row == Row.OVERLAY }.granted).isTrue()
    }
}

/** Test-button gating (ADR-045): never fire a test the OS would silently demote. */
class ReminderTestGatingTest {
    @Test
    fun `fullscreen style with the grant off on API 34+ blocks the test`() {
        assertThat(
            ReminderPermissionsStatus.blocksFullScreenTest(
                sdkInt = 35,
                style = ReminderStyle.FULLSCREEN,
                canUseFullScreenIntent = false,
            ),
        ).isTrue()
    }

    @Test
    fun `fullscreen style with the grant present fires the test`() {
        assertThat(
            ReminderPermissionsStatus.blocksFullScreenTest(
                sdkInt = 35,
                style = ReminderStyle.FULLSCREEN,
                canUseFullScreenIntent = true,
            ),
        ).isFalse()
    }

    @Test
    fun `notification style never blocks regardless of the grant`() {
        assertThat(
            ReminderPermissionsStatus.blocksFullScreenTest(
                sdkInt = 35,
                style = ReminderStyle.NOTIFICATION,
                canUseFullScreenIntent = false,
            ),
        ).isFalse()
    }

    @Test
    fun `below API 34 the grant does not exist, so the test always fires`() {
        assertThat(
            ReminderPermissionsStatus.blocksFullScreenTest(
                sdkInt = 33,
                style = ReminderStyle.FULLSCREEN,
                canUseFullScreenIntent = false,
            ),
        ).isFalse()
    }

    @Test
    fun `always-fullscreen style is also FSI-gated on API 34+`() {
        // Its notification fallback still relies on the full-screen intent.
        assertThat(
            ReminderPermissionsStatus.blocksFullScreenTest(
                sdkInt = 35,
                style = ReminderStyle.FULLSCREEN_ALWAYS,
                canUseFullScreenIntent = false,
            ),
        ).isTrue()
    }

    @Test
    fun `always-fullscreen without the overlay grant blocks the test`() {
        // A test tapped from inside the app would take over anyway (the app is
        // foreground) and LIE about what a real background reminder does.
        assertThat(
            ReminderPermissionsStatus.blocksAlwaysFullScreenTest(
                style = ReminderStyle.FULLSCREEN_ALWAYS,
                canDrawOverlays = false,
            ),
        ).isTrue()
        assertThat(
            ReminderPermissionsStatus.blocksAlwaysFullScreenTest(
                style = ReminderStyle.FULLSCREEN_ALWAYS,
                canDrawOverlays = true,
            ),
        ).isFalse()
    }

    @Test
    fun `overlay gate never applies to the other styles`() {
        for (style in listOf(ReminderStyle.NOTIFICATION, ReminderStyle.FULLSCREEN)) {
            assertThat(
                ReminderPermissionsStatus.blocksAlwaysFullScreenTest(
                    style = style,
                    canDrawOverlays = false,
                ),
            ).isFalse()
        }
    }
}
