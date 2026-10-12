package com.itsluminous.samaroh.feature.reports.domain

import java.time.LocalDate

/** The ten reports of §4.4 + ADR-027, in their Menu-list order. [routeArg] keys the nav route. */
enum class ReportType(
    val routeArg: String,
) {
    REVENUE("revenue"),
    DUES_AGING("dues_aging"),
    OCCUPANCY("occupancy"),
    EVENT_TYPES("event_types"),
    SOURCES("sources"),
    EXPENSE_SUMMARY("expense_summary"),
    PROFIT("profit"),
    INVENTORY_VALUATION("inventory_valuation"),
    COLLECTION("collection"),
    PERSONAL_EXPENSES("personal_expenses"),
    ;

    /**
     * The inventory-valuation report is a "now" snapshot of FIFO stock value — a date
     * window cannot apply to it, so it is the one report without the range filter.
     */
    val supportsDateRange: Boolean get() = this != INVENTORY_VALUATION

    /**
     * True for MONEY reports — hidden entirely from the reports home when the member's
     * `reports.view_amounts` permission is off (ADR-039; occupancy and collection-days
     * are counts/durations and stay visible).
     */
    val requiresAmounts: Boolean get() = this != OCCUPANCY && this != COLLECTION

    companion object {
        fun fromRoute(routeArg: String?): ReportType = entries.firstOrNull { it.routeArg == routeArg } ?: REVENUE
    }
}

/** Inclusive date window a report aggregates over. */
data class ReportDateRange(
    val start: LocalDate,
    val end: LocalDate,
)

/**
 * Quick-pick presets of the reports date-range filter, in CHIP ORDER (shared contract
 * `shared/docs/reports-range-filter.md`, ADR-104). [id] is the cross-platform preset id
 * (persisted form, if a platform remembers the choice). CUSTOM opens the range picker.
 */
enum class RangePreset(
    val id: String,
) {
    MINUS_12M("minus_12m"),
    MINUS_3M("minus_3m"),
    THIS_MONTH("this_month"),
    PLUS_3M("plus_3m"),
    PLUS_12M("plus_12m"),
    CUSTOM("custom"),
    ;

    companion object {
        /** Both apps default to the trailing twelve months. */
        val DEFAULT: RangePreset = MINUS_12M

        /** Chips, in the contract's order. */
        val CHIPS: List<RangePreset> = entries

        fun fromId(id: String?): RangePreset? = entries.firstOrNull { it.id == id }
    }
}

object DateRanges {
    /**
     * Resolves a preset relative to [today] (device-local date, ADR-091 "today" rule) —
     * the contract's range math, both ends inclusive:
     * - `−N months` = `today − N months` … `today` (backward-looking: what happened);
     * - `This month` = whole calendar month (dues later this month stay visible);
     * - `+N months` = `today` … `today + N months` (forward-looking: upcoming bookings/dues);
     * - CUSTOM has no math of its own — the caller supplies explicit dates; asked here it
     *   yields the [RangePreset.DEFAULT] range (the state before a picker result).
     * `today ± N months` is Java `LocalDate.plusMonths`: same day-of-month, clamped to the
     * last day when it does not exist (31 Mar − 1 month = 28/29 Feb).
     */
    fun forPreset(
        preset: RangePreset,
        today: LocalDate,
    ): ReportDateRange =
        when (preset) {
            RangePreset.MINUS_12M -> ReportDateRange(today.minusMonths(12), today)
            RangePreset.MINUS_3M -> ReportDateRange(today.minusMonths(3), today)
            RangePreset.THIS_MONTH -> ReportDateRange(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()))
            RangePreset.PLUS_3M -> ReportDateRange(today, today.plusMonths(3))
            RangePreset.PLUS_12M -> ReportDateRange(today, today.plusMonths(12))
            RangePreset.CUSTOM -> forPreset(RangePreset.DEFAULT, today)
        }
}
