package com.itsluminous.samaroh.feature.booking.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate

/** Pure logic of the events (full agenda) view: grouping, today anchor, windowing. */
class EventsAgendaTest {
    private val today: LocalDate = LocalDate.parse("2026-08-27")

    private fun booking(
        id: String,
        start: String,
        end: String = start,
    ) = Fixtures.booking(id = id, startDate = LocalDate.parse(start), endDate = LocalDate.parse(end))

    // ---- grouping ----

    @Test
    fun `groups by start date ascending with one header per date`() {
        val days =
            EventsAgenda.groupByDate(
                listOf(
                    booking("b3", "2026-09-01"),
                    booking("b1", "2026-08-20"),
                    booking("b2", "2026-08-20"),
                ),
            )

        assertThat(days.map { it.date.toString() }).containsExactly("2026-08-20", "2026-09-01").inOrder()
        assertThat(days.first().bookings.map { it.id }).containsExactly("b1", "b2")
    }

    @Test
    fun `multi day booking appears once under its start date`() {
        val days = EventsAgenda.groupByDate(listOf(booking("b1", "2026-08-20", "2026-08-23")))
        assertThat(days).hasSize(1)
        assertThat(days.single().date).isEqualTo(LocalDate.parse("2026-08-20"))
    }

    // ---- today anchor ----

    @Test
    fun `anchor lands on the first day on or after today`() {
        val days =
            EventsAgenda.groupByDate(
                listOf(
                    booking("past", "2026-08-01"),
                    booking("today", "2026-08-27"),
                    booking("future", "2026-09-05"),
                ),
            )
        assertThat(EventsAgenda.todayAnchorIndex(days, today)).isEqualTo(1)
    }

    @Test
    fun `anchor falls back to the last day when everything is past`() {
        val days = EventsAgenda.groupByDate(listOf(booking("a", "2026-05-01"), booking("b", "2026-06-01")))
        assertThat(EventsAgenda.todayAnchorIndex(days, today)).isEqualTo(1)
    }

    @Test
    fun `flat anchor counts one header plus rows per earlier day`() {
        val days =
            EventsAgenda.groupByDate(
                listOf(
                    booking("p1", "2026-08-01"),
                    booking("p2", "2026-08-01"),
                    booking("p3", "2026-08-10"),
                    booking("t", "2026-08-27"),
                ),
            )
        // day0: header + 2 rows = 3 items; day1: header + 1 row = 2 items → anchor at 5.
        assertThat(EventsAgenda.flatAnchorIndex(days, today)).isEqualTo(5)
    }

    @Test
    fun `empty agenda anchors nowhere`() {
        assertThat(EventsAgenda.flatAnchorIndex(emptyList(), today)).isEqualTo(-1)
    }

    // ---- windowing ----

    @Test
    fun `initial window spans the configured months around today`() {
        val window = EventsAgenda.initialWindow(today)
        assertThat(window.from).isEqualTo(LocalDate.parse("2026-06-01"))
        assertThat(window.to).isEqualTo(LocalDate.parse("2026-12-31"))
    }

    @Test
    fun `expandPast grows by the step and clamps at the earliest booking month`() {
        val window = EventsAgenda.initialWindow(today)
        val bounds = LocalDate.parse("2026-03-15")..LocalDate.parse("2027-06-01")

        val expanded = EventsAgenda.expandPast(window, bounds)

        assertThat(expanded?.from).isEqualTo(LocalDate.parse("2026-03-01"))
        // Next expansion has nothing left.
        assertThat(EventsAgenda.expandPast(expanded!!, bounds)).isNull()
    }

    @Test
    fun `expandFuture grows by the step and clamps at the latest booking month`() {
        val window = EventsAgenda.initialWindow(today)
        val bounds = LocalDate.parse("2026-01-01")..LocalDate.parse("2027-02-10")

        val expanded = EventsAgenda.expandFuture(window, bounds)

        assertThat(expanded?.to).isEqualTo(LocalDate.parse("2027-02-28"))
        assertThat(EventsAgenda.expandFuture(expanded!!, bounds)).isNull()
    }

    @Test
    fun `hasMore flags reflect bookings outside the window`() {
        val window = EventsAgenda.Window(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-12-31"))

        assertThat(EventsAgenda.hasMorePast(window, LocalDate.parse("2026-01-01")..LocalDate.parse("2026-07-01"))).isTrue()
        assertThat(EventsAgenda.hasMorePast(window, LocalDate.parse("2026-06-01")..LocalDate.parse("2026-07-01"))).isFalse()
        assertThat(EventsAgenda.hasMoreFuture(window, LocalDate.parse("2026-06-01")..LocalDate.parse("2027-07-01"))).isTrue()
        assertThat(EventsAgenda.hasMoreFuture(window, LocalDate.parse("2026-06-01")..LocalDate.parse("2026-12-31"))).isFalse()
        assertThat(EventsAgenda.hasMorePast(window, null)).isFalse()
        assertThat(EventsAgenda.hasMoreFuture(window, null)).isFalse()
    }
}
