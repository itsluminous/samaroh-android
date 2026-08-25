package com.itsluminous.samaroh.core.google.calendar

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.Instant

class CalendarSyncPlannerTest {
    private fun fingerprintOf(booking: Booking) = GcalEventMapper.fingerprint(booking, paidPaise = 0)

    @Test
    fun `empty state bulk-pushes every live booking as a create`() {
        val bookings = listOf(Fixtures.booking(id = "b-1"), Fixtures.booking(id = "b-2"))
        val plan = CalendarSyncPlanner.plan(bookings, emptyMap(), ::fingerprintOf)
        assertThat(plan.creates.map { it.id }).containsExactly("b-1", "b-2")
        assertThat(plan.updates).isEmpty()
        assertThat(plan.deletes).isEmpty()
    }

    @Test
    fun `unchanged bookings are skipped`() {
        val booking = Fixtures.booking(id = "b-1")
        val state = mapOf("b-1" to SyncedEventState(eventId = "ev-1", fingerprint = fingerprintOf(booking)))
        val plan = CalendarSyncPlanner.plan(listOf(booking), state, ::fingerprintOf)
        assertThat(plan.isEmpty).isTrue()
    }

    @Test
    fun `changed bookings become updates with the pushed event id`() {
        val original = Fixtures.booking(id = "b-1")
        val state = mapOf("b-1" to SyncedEventState(eventId = "ev-1", fingerprint = fingerprintOf(original)))
        val edited = original.copy(customerName = "new-name")
        val plan = CalendarSyncPlanner.plan(listOf(edited), state, ::fingerprintOf)
        assertThat(plan.creates).isEmpty()
        assertThat(plan.updates).hasSize(1)
        assertThat(
            plan.updates
                .single()
                .second.eventId,
        ).isEqualTo("ev-1")
    }

    @Test
    fun `cancelled bookings with a pushed event become deletes`() {
        val booking = Fixtures.booking(id = "b-1", status = BookingStatus.CANCELLED)
        val state = mapOf("b-1" to SyncedEventState(eventId = "ev-1", fingerprint = "old"))
        val plan = CalendarSyncPlanner.plan(listOf(booking), state, ::fingerprintOf)
        assertThat(plan.deletes).containsExactly("b-1", SyncedEventState("ev-1", "old"))
        assertThat(plan.creates).isEmpty()
    }

    @Test
    fun `cancelled bookings never pushed are ignored`() {
        val booking = Fixtures.booking(id = "b-1", status = BookingStatus.CANCELLED)
        val plan = CalendarSyncPlanner.plan(listOf(booking), emptyMap(), ::fingerprintOf)
        assertThat(plan.isEmpty).isTrue()
    }

    @Test
    fun `tombstoned bookings with a pushed event become deletes`() {
        val booking = Fixtures.booking(id = "b-1").copy(deletedAt = Instant.parse("2026-08-25T10:00:00Z"))
        val state = mapOf("b-1" to SyncedEventState(eventId = "ev-1", fingerprint = "old"))
        val plan = CalendarSyncPlanner.plan(listOf(booking), state, ::fingerprintOf)
        assertThat(plan.deletes.keys).containsExactly("b-1")
    }

    @Test
    fun `state entries for locally-vanished bookings become deletes`() {
        val state = mapOf("b-gone" to SyncedEventState(eventId = "ev-9", fingerprint = "x"))
        val plan = CalendarSyncPlanner.plan(emptyList(), state, ::fingerprintOf)
        assertThat(plan.deletes.keys).containsExactly("b-gone")
    }

    @Test
    fun `mixed plan handles creates updates and deletes together`() {
        val unchanged = Fixtures.booking(id = "b-same")
        val edited = Fixtures.booking(id = "b-edit")
        val fresh = Fixtures.booking(id = "b-new")
        val cancelled = Fixtures.booking(id = "b-cancel", status = BookingStatus.CANCELLED)
        val state =
            mapOf(
                "b-same" to SyncedEventState("ev-1", fingerprintOf(unchanged)),
                "b-edit" to SyncedEventState("ev-2", "stale"),
                "b-cancel" to SyncedEventState("ev-3", "stale"),
            )
        val plan = CalendarSyncPlanner.plan(listOf(unchanged, edited, fresh, cancelled), state, ::fingerprintOf)
        assertThat(plan.creates.map { it.id }).containsExactly("b-new")
        assertThat(plan.updates.map { it.first.id }).containsExactly("b-edit")
        assertThat(plan.deletes.keys).containsExactly("b-cancel")
    }
}
