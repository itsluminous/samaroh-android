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

/** Quick-pick presets for the date-range filter; CUSTOM opens the range picker. */
enum class RangePreset {
    THIS_MONTH,
    LAST_3_MONTHS,
    LAST_12_MONTHS,
    CUSTOM,
}

object DateRanges {
    /**
     * Resolves a preset relative to [today]. Month presets span whole calendar months and
     * run through the END of the current month so upcoming bookings stay visible.
     */
    fun forPreset(
        preset: RangePreset,
        today: LocalDate,
    ): ReportDateRange {
        val endOfMonth = today.withDayOfMonth(today.lengthOfMonth())
        return when (preset) {
            RangePreset.THIS_MONTH -> ReportDateRange(today.withDayOfMonth(1), endOfMonth)
            RangePreset.LAST_3_MONTHS -> ReportDateRange(today.minusMonths(2).withDayOfMonth(1), endOfMonth)
            RangePreset.LAST_12_MONTHS, RangePreset.CUSTOM ->
                ReportDateRange(today.minusMonths(11).withDayOfMonth(1), endOfMonth)
        }
    }
}
