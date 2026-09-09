package com.itsluminous.samaroh.feature.booking.reminders

import android.app.NotificationManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ADR-074: both full-screen styles repeat their sound until acknowledged (insistent
 * notification / popup loop); the plain NOTIFICATION style stays single-play; the
 * popup loop respects Do Not Disturb.
 */
class ReminderRepeatPolicyTest {
    @Test
    fun `notification style is never insistent`() {
        assertThat(ReminderRepeatPolicy.insistent(ReminderStyle.NOTIFICATION)).isFalse()
    }

    @Test
    fun `both full-screen styles are insistent`() {
        assertThat(ReminderRepeatPolicy.insistent(ReminderStyle.FULLSCREEN)).isTrue()
        assertThat(ReminderRepeatPolicy.insistent(ReminderStyle.FULLSCREEN_ALWAYS)).isTrue()
    }

    @Test
    fun `popup loops only while DND is fully off`() {
        assertThat(ReminderRepeatPolicy.shouldLoopInPopup(NotificationManager.INTERRUPTION_FILTER_ALL)).isTrue()
        assertThat(ReminderRepeatPolicy.shouldLoopInPopup(NotificationManager.INTERRUPTION_FILTER_PRIORITY)).isFalse()
        assertThat(ReminderRepeatPolicy.shouldLoopInPopup(NotificationManager.INTERRUPTION_FILTER_ALARMS)).isFalse()
        assertThat(ReminderRepeatPolicy.shouldLoopInPopup(NotificationManager.INTERRUPTION_FILTER_NONE)).isFalse()
        assertThat(ReminderRepeatPolicy.shouldLoopInPopup(NotificationManager.INTERRUPTION_FILTER_UNKNOWN)).isFalse()
    }
}
