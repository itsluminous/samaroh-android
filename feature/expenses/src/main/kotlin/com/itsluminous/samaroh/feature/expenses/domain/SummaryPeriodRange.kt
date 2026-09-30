package com.itsluminous.samaroh.feature.expenses.domain

import com.itsluminous.samaroh.core.data.settings.SummaryPeriod
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Resolves a [SummaryPeriod] to the inclusive `expense_date` window the home summary card
 * totals over (ADR-091). Pure and clock-free: callers pass "today" as resolved by
 * [today], which uses the SAME local-date rule as the add-entry form's default
 * `expense_date` (`LocalDate.now(clock.withZone(ZoneId.systemDefault()))`) — so an entry
 * dated "today" by the user always lands inside "This month" for that user, whatever the
 * UTC clock says.
 */
object SummaryPeriodRange {
    /** Inclusive first..last `expense_date` for [period] as of [today]; `null` = unbounded (All time). */
    fun bounds(
        period: SummaryPeriod,
        today: LocalDate,
    ): ClosedRange<LocalDate>? =
        when (period) {
            SummaryPeriod.THIS_MONTH -> today.withDayOfMonth(1)..today.withDayOfMonth(today.lengthOfMonth())
            SummaryPeriod.THIS_YEAR -> today.withDayOfYear(1)..today.withDayOfYear(today.lengthOfYear())
            SummaryPeriod.ALL_TIME -> null
        }

    /**
     * The device-local calendar date [clock] currently points at. The injected app clock is
     * UTC (`DataModule`), so it is re-zoned to [zone] (default: the device zone) before the
     * date is read — the expense-date convention shared with the add-entry form.
     */
    fun today(
        clock: Clock,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate = LocalDate.now(clock.withZone(zone))
}
