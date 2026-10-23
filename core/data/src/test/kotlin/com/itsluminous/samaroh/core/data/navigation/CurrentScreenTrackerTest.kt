package com.itsluminous.samaroh.core.data.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/** [CurrentScreenTracker] transitions (ADR-108): screen vs overlay precedence, set-only semantics. */
class CurrentScreenTrackerTest {
    @Before
    fun reset() = CurrentScreenTracker.reset()

    @After
    fun tearDown() = CurrentScreenTracker.reset()

    @Test
    fun `nothing reported resolves to null`() {
        assertThat(CurrentScreenTracker.state.value.effective).isNull()
    }

    @Test
    fun `screen then overlay - overlay wins, closing it returns to the screen`() {
        CurrentScreenTracker.setScreen(HelpScreenIds.BOOKING_CALENDAR)
        assertThat(CurrentScreenTracker.state.value.effective).isEqualTo("booking_calendar")

        CurrentScreenTracker.setOverlay(HelpScreenIds.BOOKING_CARD)
        assertThat(CurrentScreenTracker.state.value.effective).isEqualTo("booking_card")
        assertThat(CurrentScreenTracker.state.value.screen).isEqualTo("booking_calendar")

        // Record payment opens over the card: the deeper sheet is what the user sees.
        CurrentScreenTracker.setOverlay(HelpScreenIds.RECORD_PAYMENT)
        assertThat(CurrentScreenTracker.state.value.effective).isEqualTo("record_payment")

        CurrentScreenTracker.setOverlay(HelpScreenIds.BOOKING_CARD)
        assertThat(CurrentScreenTracker.state.value.effective).isEqualTo("booking_card")

        CurrentScreenTracker.setOverlay(null)
        assertThat(CurrentScreenTracker.state.value.effective).isEqualTo("booking_calendar")
    }

    @Test
    fun `a new screen clears a stale overlay`() {
        CurrentScreenTracker.setScreen(HelpScreenIds.BOOKING_CALENDAR)
        CurrentScreenTracker.setOverlay(HelpScreenIds.BOOKING_CARD)
        CurrentScreenTracker.setScreen(HelpScreenIds.EXPENSES_HOME)
        assertThat(CurrentScreenTracker.state.value).isEqualTo(CurrentScreen(screen = "expenses_home", overlay = null))
    }

    @Test
    fun `last writer wins across tab switches`() {
        CurrentScreenTracker.setScreen(HelpScreenIds.NOTES)
        CurrentScreenTracker.setScreen(HelpScreenIds.FILES)
        CurrentScreenTracker.setScreen(HelpScreenIds.HELP)
        assertThat(CurrentScreenTracker.state.value.effective).isEqualTo("help")
    }

    @Test
    fun `help navigation holder consumes only the pending target`() {
        val holder = HelpNavigationHolder()
        assertThat(holder.pending.value).isNull()
        val first = HelpTarget(HelpDeepLinkIds.BOOKING_FORM)
        holder.set(first)
        val second = HelpTarget(HelpDeepLinkIds.MENU_SETTINGS)
        holder.set(second)
        holder.consume(first)
        assertThat(holder.pending.value).isEqualTo(second)
        holder.consume(second)
        assertThat(holder.pending.value).isNull()
        holder.set(first)
        holder.clear()
        assertThat(holder.pending.value).isNull()
    }
}
