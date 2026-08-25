package com.itsluminous.samaroh.feature.reports.domain

import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingSource
import java.time.LocalDate
import java.time.YearMonth

/*
 * Pure result models of the report calculators. All money is Long paise (ADR-002);
 * rendering goes through AmountFormatter/AmountText only.
 */

/** Revenue summary (§4.4 #1): one stacked bar per month. */
data class RevenueMonth(
    val month: YearMonth,
    val collectedPaise: Long,
    val outstandingPaise: Long,
) {
    val totalPaise: Long get() = collectedPaise + outstandingPaise
}

/** Dues-aging buckets (§4.4 #2). */
enum class AgingBucket {
    DAYS_0_7,
    DAYS_8_30,
    DAYS_31_90,
    DAYS_90_PLUS,
}

/** One booking with money still due, aged from its event end. */
data class AgingEntry(
    val booking: Booking,
    val duePaise: Long,
    val daysOverdue: Long,
) {
    val bucket: AgingBucket
        get() =
            when {
                daysOverdue <= 7 -> AgingBucket.DAYS_0_7
                daysOverdue <= 30 -> AgingBucket.DAYS_8_30
                daysOverdue <= 90 -> AgingBucket.DAYS_31_90
                else -> AgingBucket.DAYS_90_PLUS
            }
}

/** Occupancy (§4.4 #3): booked days and utilization for one month. */
data class OccupancyMonth(
    val month: YearMonth,
    val bookedDays: Int,
    val daysInMonth: Int,
) {
    val utilizationPercent: Int get() = if (daysInMonth == 0) 0 else (bookedDays * 100) / daysInMonth
}

/** Event-type breakdown (§4.4 #4). */
data class EventTypeRow(
    val eventType: String,
    val eventIcon: String,
    val bookings: Int,
    val revenuePaise: Long,
)

/** Booking-source breakdown (§4.4 #5); [source] is null for bookings without one. */
data class SourceRow(
    val source: BookingSource?,
    val bookings: Int,
    val revenuePaise: Long,
)

/** Expense summary (§4.4 #6): net spend (paid − received) per party. */
data class PartyExpenseRow(
    val partyId: String,
    val partyName: String,
    val spendPaise: Long,
)

/** Profit view (§4.4 #7): cash-basis income vs expenses for one month. */
data class ProfitMonth(
    val month: YearMonth,
    val incomePaise: Long,
    val expensePaise: Long,
) {
    val netPaise: Long get() = incomePaise - expensePaise
}

/** Inventory valuation (§4.4 #8): current FIFO value of one item. */
data class ValuationRow(
    val masterItemId: String,
    val name: String,
    val unit: String,
    val quantity: Double,
    val valuePaise: Long,
)

/** Collection efficiency (§4.4 #9): one fully-paid booking and how long it took. */
data class CollectionEntry(
    val booking: Booking,
    val fullyPaidOn: LocalDate,
    val daysToFullPayment: Long,
)

/** Collection efficiency aggregate: overall + per-month averages. */
data class CollectionResult(
    val averageDays: Double?,
    val entries: List<CollectionEntry>,
    val monthly: List<CollectionMonth>,
)

/** Average days to full payment for bookings that ended in [month]. */
data class CollectionMonth(
    val month: YearMonth,
    val averageDays: Double,
)
