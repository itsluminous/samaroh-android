package com.itsluminous.samaroh.feature.expenses.domain

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.settings.SummaryPeriod
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** ADR-091: period → inclusive expense_date window, resolved on the DEVICE-local date. */
class SummaryPeriodRangeTest {
    @Test
    fun `this month spans the first through the last day of today's month`() {
        val range = SummaryPeriodRange.bounds(SummaryPeriod.THIS_MONTH, LocalDate.of(2026, 8, 15))!!
        assertThat(range.start).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(range.endInclusive).isEqualTo(LocalDate.of(2026, 8, 31))
    }

    @Test
    fun `this month respects short months and leap Februaries`() {
        assertThat(SummaryPeriodRange.bounds(SummaryPeriod.THIS_MONTH, LocalDate.of(2028, 2, 10))!!.endInclusive)
            .isEqualTo(LocalDate.of(2028, 2, 29))
        assertThat(SummaryPeriodRange.bounds(SummaryPeriod.THIS_MONTH, LocalDate.of(2027, 2, 1))!!.endInclusive)
            .isEqualTo(LocalDate.of(2027, 2, 28))
        assertThat(SummaryPeriodRange.bounds(SummaryPeriod.THIS_MONTH, LocalDate.of(2026, 4, 30))!!.endInclusive)
            .isEqualTo(LocalDate.of(2026, 4, 30))
    }

    @Test
    fun `this year spans 1 Jan through 31 Dec of today's year`() {
        val range = SummaryPeriodRange.bounds(SummaryPeriod.THIS_YEAR, LocalDate.of(2026, 8, 15))!!
        assertThat(range.start).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(range.endInclusive).isEqualTo(LocalDate.of(2026, 12, 31))
    }

    @Test
    fun `all time has no bounds`() {
        assertThat(SummaryPeriodRange.bounds(SummaryPeriod.ALL_TIME, LocalDate.of(2026, 8, 15))).isNull()
    }

    @Test
    fun `today follows the device zone, not the UTC app clock`() {
        // 31 Dec 20:30 UTC is already 1 Jan 02:00 in India — the month AND year roll over there.
        val utcClock = Clock.fixed(Instant.parse("2025-12-31T20:30:00Z"), ZoneOffset.UTC)

        val india = SummaryPeriodRange.today(utcClock, ZoneId.of("Asia/Kolkata"))
        val utc = SummaryPeriodRange.today(utcClock, ZoneOffset.UTC)

        assertThat(india).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(utc).isEqualTo(LocalDate.of(2025, 12, 31))
        assertThat(SummaryPeriodRange.bounds(SummaryPeriod.THIS_MONTH, india)!!.start).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(SummaryPeriodRange.bounds(SummaryPeriod.THIS_YEAR, utc)!!.endInclusive).isEqualTo(LocalDate.of(2025, 12, 31))
    }

    @Test
    fun `today matches the add-entry default date rule for a west-of-UTC device`() {
        // 1 Sep 03:00 UTC is still 31 Aug in Los Angeles — an entry saved "today" there
        // gets expense_date 31 Aug and must fall inside that device's "This month".
        val utcClock = Clock.fixed(Instant.parse("2026-09-01T03:00:00Z"), ZoneOffset.UTC)
        val la = SummaryPeriodRange.today(utcClock, ZoneId.of("America/Los_Angeles"))
        val month = SummaryPeriodRange.bounds(SummaryPeriod.THIS_MONTH, la)!!
        assertThat(la).isEqualTo(LocalDate.of(2026, 8, 31))
        assertThat(la in month).isTrue()
        assertThat(month.start).isEqualTo(LocalDate.of(2026, 8, 1))
    }
}
