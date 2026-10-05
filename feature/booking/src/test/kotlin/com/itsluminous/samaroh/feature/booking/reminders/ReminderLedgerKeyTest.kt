package com.itsluminous.samaroh.feature.booking.reminders

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderKind
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Pure pieces of the reminder ledger + snooze design (ADR-094). */
class ReminderLedgerKeyTest {
    @Test
    fun `upcoming key round-trips and changes with any of booking, offset, start date`() {
        val key = ReminderLedgerKey.Upcoming("b-1", 1, LocalDate.of(2026, 10, 6))
        assertThat(ReminderLedgerKey.decode(key.encode())).isEqualTo(key)
        assertThat(key.encode()).isNotEqualTo(key.copy(daysAway = 3).encode())
        assertThat(key.encode()).isNotEqualTo(key.copy(startDate = LocalDate.of(2026, 10, 7)).encode())
        assertThat(key.encode()).isNotEqualTo(key.copy(bookingId = "b-2").encode())
    }

    @Test
    fun `row keys round-trip for payment and follow-up`() {
        val payment = ReminderLedgerKey.Row("r-1", "b-1", followUp = false)
        val followUp = ReminderLedgerKey.Row("r-1", "b-1", followUp = true)
        assertThat(ReminderLedgerKey.decode(payment.encode())).isEqualTo(payment)
        assertThat(ReminderLedgerKey.decode(followUp.encode())).isEqualTo(followUp)
        assertThat(payment.encode()).isNotEqualTo(followUp.encode())
    }

    @Test
    fun `garbage decodes to null instead of throwing`() {
        assertThat(ReminderLedgerKey.decode(null)).isNull()
        assertThat(ReminderLedgerKey.decode("")).isNull()
        assertThat(ReminderLedgerKey.decode("upcoming|b|notanumber|2026-01-01")).isNull()
        assertThat(ReminderLedgerKey.decode("weird|x")).isNull()
    }

    @Test
    fun `entry round-trips with and without a snooze time, and only SCHEDULED leaves planning open`() {
        val fired = ReminderLedgerEntry(ReminderFireState.FIRED, LocalDate.of(2026, 10, 6))
        val snoozed = ReminderLedgerEntry(ReminderFireState.SNOOZED, LocalDate.of(2026, 10, 6), snoozedUntilMillis = 1_700_000_000_000L)
        assertThat(ReminderLedgerEntry.decode(fired.encode())).isEqualTo(fired)
        assertThat(ReminderLedgerEntry.decode(snoozed.encode())).isEqualTo(snoozed)
        assertThat(ReminderLedgerEntry.decode("bogus|2026-10-06")).isNull()

        assertThat(ReminderLedgerEntry(ReminderFireState.SCHEDULED, LocalDate.MIN).blocksPlanning).isFalse()
        assertThat(fired.blocksPlanning).isTrue()
        assertThat(snoozed.blocksPlanning).isTrue()
        assertThat(ReminderLedgerEntry(ReminderFireState.ACKED, LocalDate.MIN).blocksPlanning).isTrue()
    }
}

class SnoozePresetsTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 10, 5, 19, 38, 0, 0, kolkata)

    @Test
    fun `relative presets add their duration`() {
        assertThat(SnoozePresets.fireAt(SnoozePreset.TEN_MINUTES, now)).isEqualTo(now.plusMinutes(10).toInstant())
        assertThat(SnoozePresets.fireAt(SnoozePreset.THIRTY_MINUTES, now)).isEqualTo(now.plusMinutes(30).toInstant())
        assertThat(SnoozePresets.fireAt(SnoozePreset.ONE_HOUR, now)).isEqualTo(now.plusHours(1).toInstant())
        assertThat(SnoozePresets.fireAt(SnoozePreset.THREE_HOURS, now)).isEqualTo(now.plusHours(3).toInstant())
    }

    @Test
    fun `tomorrow morning is the NEXT local day at 09-00 in the user's zone`() {
        val fireAt = SnoozePresets.fireAt(SnoozePreset.TOMORROW_MORNING, now).atZone(kolkata)
        assertThat(fireAt.toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 6))
        assertThat(fireAt.toLocalTime()).isEqualTo(SnoozePresets.MORNING)
        // 09:00 IST == 03:30Z — the instant is zone-correct, not a UTC 09:00.
        assertThat(fireAt.toInstant()).isEqualTo(Instant.parse("2026-10-06T03:30:00Z"))
    }

    @Test
    fun `tomorrow morning snoozed before 09-00 is still tomorrow, never later today`() {
        val early = ZonedDateTime.of(2026, 10, 5, 7, 0, 0, 0, kolkata)
        assertThat(SnoozePresets.fireAt(SnoozePreset.TOMORROW_MORNING, early).atZone(kolkata).toLocalDate())
            .isEqualTo(LocalDate.of(2026, 10, 6))
    }

    @Test
    fun `tomorrow morning across a DST change keeps 09-00 wall clock`() {
        val london = ZoneId.of("Europe/London")
        // 2026-10-25 is the UK autumn clock change (01:00 UTC → 00:00 UTC).
        val before = ZonedDateTime.of(2026, 10, 24, 22, 0, 0, 0, london)
        val fireAt = SnoozePresets.fireAt(SnoozePreset.TOMORROW_MORNING, before).atZone(london)
        assertThat(fireAt.toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 25))
        assertThat(fireAt.toLocalTime()).isEqualTo(SnoozePresets.MORNING)
    }

    @Test
    fun `wire values round-trip`() {
        SnoozePreset.entries.forEach { assertThat(SnoozePreset.fromWire(it.wire)).isEqualTo(it) }
        assertThat(SnoozePreset.fromWire("nope")).isNull()
    }
}

/** Re-fire gate: a snooze never resurrects a reminder whose booking changed state (ADR-064 rules). */
class SnoozePolicyTest {
    private val today = LocalDate.of(2026, 10, 5)

    private fun row(
        status: ReminderStatus = ReminderStatus.PENDING,
        kind: ReminderKind = ReminderKind.PAYMENT,
    ) = PaymentReminder(
        id = "r",
        bookingId = "b",
        businessId = Fixtures.BUSINESS_ID,
        remindOn = today,
        status = status,
        amountDueSnapshotPaise = 100L,
        kind = kind,
        createdAt = Fixtures.NOW,
        updatedAt = Fixtures.NOW,
    )

    @Test
    fun `upcoming - live booking that has not started is relevant`() {
        val booking = Fixtures.booking(startDate = today.plusDays(1))
        assertThat(SnoozePolicy.upcomingStillRelevant(booking, today)).isTrue()
        assertThat(SnoozePolicy.upcomingStillRelevant(booking.copy(startDate = today), today)).isTrue()
    }

    @Test
    fun `upcoming - deleted, cancelled, missing or already-started bookings are dropped`() {
        val booking = Fixtures.booking(startDate = today.plusDays(1))
        assertThat(SnoozePolicy.upcomingStillRelevant(null, today)).isFalse()
        assertThat(SnoozePolicy.upcomingStillRelevant(booking.copy(deletedAt = Fixtures.NOW), today)).isFalse()
        assertThat(SnoozePolicy.upcomingStillRelevant(booking.copy(status = BookingStatus.CANCELLED), today)).isFalse()
        assertThat(SnoozePolicy.upcomingStillRelevant(booking.copy(startDate = today.minusDays(1)), today)).isFalse()
    }

    @Test
    fun `payment row - pending on a live unpaid booking is relevant, truly paid is not`() {
        val booking = Fixtures.booking(totalAmountPaise = 1_000L)
        assertThat(SnoozePolicy.rowStillRelevant(row(), booking, duePaise = 400L, followUp = false)).isTrue()
        assertThat(SnoozePolicy.rowStillRelevant(row(), booking, duePaise = 0L, followUp = false)).isFalse()
        // Unknown total (0) is NOT "paid" (ADR-064): the reminder stays.
        assertThat(SnoozePolicy.rowStillRelevant(row(), booking.copy(totalAmountPaise = 0L), duePaise = 0L, followUp = false)).isTrue()
    }

    @Test
    fun `payment row - acted-on row, cancelled or deleted booking are dropped`() {
        val booking = Fixtures.booking(totalAmountPaise = 1_000L)
        assertThat(SnoozePolicy.rowStillRelevant(null, booking, 400L, followUp = false)).isFalse()
        assertThat(SnoozePolicy.rowStillRelevant(row(status = ReminderStatus.CONFIRMED), booking, 400L, followUp = false)).isFalse()
        assertThat(SnoozePolicy.rowStillRelevant(row(status = ReminderStatus.DISMISSED), booking, 400L, followUp = false)).isFalse()
        assertThat(SnoozePolicy.rowStillRelevant(row(), booking.copy(status = BookingStatus.CANCELLED), 400L, followUp = false)).isFalse()
        assertThat(SnoozePolicy.rowStillRelevant(row(), booking.copy(deletedAt = Fixtures.NOW), 400L, followUp = false)).isFalse()
        assertThat(SnoozePolicy.rowStillRelevant(row(), null, 400L, followUp = false)).isFalse()
    }

    @Test
    fun `follow-up row - only while the booking is still tentative`() {
        val tentative = Fixtures.booking(status = BookingStatus.TENTATIVE, totalAmountPaise = 0L)
        val followUp = row(kind = ReminderKind.FOLLOW_UP)
        assertThat(SnoozePolicy.rowStillRelevant(followUp, tentative, 0L, followUp = true)).isTrue()
        assertThat(SnoozePolicy.rowStillRelevant(followUp, tentative.copy(status = BookingStatus.CONFIRMED), 0L, followUp = true)).isFalse()
    }
}
