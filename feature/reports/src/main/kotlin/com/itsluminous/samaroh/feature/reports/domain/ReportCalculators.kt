package com.itsluminous.samaroh.feature.reports.domain

import com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.TxnType
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/*
 * Pure aggregation logic for the nine reports (§4.4). No Android, no IO, no clocks —
 * everything a calculator needs comes in as parameters, so each one is unit-testable
 * over fixture data. All money stays Long paise (ADR-002).
 */

/** Every month from the range start month through the range end month, in order. */
internal fun ReportDateRange.months(): List<YearMonth> {
    val first = YearMonth.from(start)
    val last = YearMonth.from(end)
    val months = mutableListOf<YearMonth>()
    var cursor = first
    while (!cursor.isAfter(last)) {
        months += cursor
        cursor = cursor.plusMonths(1)
    }
    return months
}

/** Bookings that count toward money reports: live rows minus cancelled ones. */
private fun List<Booking>.countable(): List<Booking> = filter { it.status != BookingStatus.CANCELLED && it.deletedAt == null }

/** Sum of live payments per booking id. */
private fun List<BookingPayment>.paidByBooking(): Map<String, Long> =
    filter { it.deletedAt == null }
        .groupBy { it.bookingId }
        .mapValues { (_, payments) -> payments.sumOf { it.amountPaise } }

/**
 * Monthly inventory purchases: live `add` transactions valued at quantity × unit price
 * (rounded to whole paise per transaction), bucketed by the [zone]-local month of their
 * transaction time. Counted as spend in the money reports without creating expense
 * ledger rows — web-parity with `inventoryPurchasesByMonth` (ADR-026).
 */
internal fun inventoryPurchasesByMonth(
    purchases: List<InventoryTransaction>,
    range: ReportDateRange,
    zone: ZoneId,
): Map<YearMonth, Long> {
    val byMonth = mutableMapOf<YearMonth, Long>()
    purchases
        .filter { it.transactionType == TxnType.ADD && it.deletedAt == null }
        .forEach { txn ->
            val date = txn.transactionDate.atZone(zone).toLocalDate()
            if (date in range.start..range.end) {
                val month = YearMonth.from(date)
                byMonth[month] = (byMonth[month] ?: 0L) + (txn.quantity * txn.unitPricePaise).roundToLong()
            }
        }
    return byMonth
}

/** §4.4 #1 — collected vs outstanding per month, attributed to the booking's start month. */
object RevenueSummaryCalculator {
    fun calculate(
        bookings: List<Booking>,
        payments: List<BookingPayment>,
        range: ReportDateRange,
    ): List<RevenueMonth> {
        val paid = payments.paidByBooking()
        val byMonth =
            bookings
                .countable()
                .filter { it.startDate in range.start..range.end }
                .groupBy { YearMonth.from(it.startDate) }
        return range.months().map { month ->
            var collected = 0L
            var outstanding = 0L
            byMonth[month].orEmpty().forEach { booking ->
                val paidPaise = paid[booking.id] ?: 0L
                collected += minOf(booking.totalAmountPaise, paidPaise)
                outstanding += (booking.totalAmountPaise - paidPaise).coerceAtLeast(0L)
            }
            RevenueMonth(month = month, collectedPaise = collected, outstandingPaise = outstanding)
        }
    }
}

/** §4.4 #2 — bookings with due > 0, aged by days since their event end (as of [today]). */
object DuesAgingCalculator {
    fun calculate(
        bookings: List<Booking>,
        payments: List<BookingPayment>,
        today: LocalDate,
    ): List<AgingEntry> {
        val paid = payments.paidByBooking()
        return bookings
            .countable()
            .mapNotNull { booking ->
                val due = booking.totalAmountPaise - (paid[booking.id] ?: 0L)
                if (due <= 0L) return@mapNotNull null
                val daysOverdue = ChronoUnit.DAYS.between(booking.endDate, today).coerceAtLeast(0L)
                AgingEntry(booking = booking, duePaise = due, daysOverdue = daysOverdue)
            }.sortedWith(compareByDescending<AgingEntry> { it.daysOverdue }.thenByDescending { it.duePaise })
    }

    /** Total due per bucket, always carrying all four buckets (zero when empty). */
    fun bucketTotals(entries: List<AgingEntry>): Map<AgingBucket, Long> =
        AgingBucket.entries.associateWith { bucket ->
            entries.filter { it.bucket == bucket }.sumOf { it.duePaise }
        }
}

/** §4.4 #3 — distinct booked days per month within the range (multi-day spans count each day). */
object OccupancyCalculator {
    fun calculate(
        bookings: List<Booking>,
        range: ReportDateRange,
    ): List<OccupancyMonth> {
        val bookedDates = mutableSetOf<LocalDate>()
        bookings.countable().forEach { booking ->
            var day = maxOf(booking.startDate, range.start)
            val last = minOf(booking.endDate, range.end)
            while (!day.isAfter(last)) {
                bookedDates += day
                day = day.plusDays(1)
            }
        }
        return range.months().map { month ->
            val daysInMonth =
                (1..month.lengthOfMonth())
                    .map { month.atDay(it) }
                    .count { it in range.start..range.end }
            val booked = bookedDates.count { YearMonth.from(it) == month }
            OccupancyMonth(month = month, bookedDays = booked, daysInMonth = daysInMonth)
        }
    }
}

/** §4.4 #4 — count and revenue per event type, biggest revenue first. */
object EventTypeBreakdownCalculator {
    fun calculate(
        bookings: List<Booking>,
        range: ReportDateRange,
    ): List<EventTypeRow> =
        bookings
            .countable()
            .filter { it.startDate in range.start..range.end }
            .groupBy { it.eventType }
            .map { (type, group) ->
                EventTypeRow(
                    eventType = type,
                    eventIcon = group.first().eventIcon,
                    bookings = group.size,
                    revenuePaise = group.sumOf { it.totalAmountPaise },
                )
            }.sortedByDescending { it.revenuePaise }
}

/** §4.4 #5 — count and revenue per booking source; bookings without one group under null. */
object BookingSourceBreakdownCalculator {
    fun calculate(
        bookings: List<Booking>,
        range: ReportDateRange,
    ): List<SourceRow> =
        bookings
            .countable()
            .filter { it.startDate in range.start..range.end }
            .groupBy { it.source }
            .map { (source, group) ->
                SourceRow(source = source, bookings = group.size, revenuePaise = group.sumOf { it.totalAmountPaise })
            }.sortedByDescending { it.revenuePaise }
}

/** §4.4 #6 — net spend (paid − received) per party, biggest first. */
object ExpenseSummaryCalculator {
    fun calculate(
        expenses: List<Expense>,
        partyNames: Map<String, String>,
        unknownPartyName: String,
        personalPartyIds: Set<String> = emptySet(),
    ): List<PartyExpenseRow> =
        expenses
            .filter { it.deletedAt == null && it.partyId !in personalPartyIds }
            .groupBy { it.partyId }
            .map { (partyId, group) ->
                PartyExpenseRow(
                    partyId = partyId,
                    partyName = partyNames[partyId] ?: unknownPartyName,
                    spendPaise = group.sumOf { if (it.direction == ExpenseDirection.PAID) it.amountPaise else -it.amountPaise },
                )
            }.sortedByDescending { it.spendPaise }

    fun top(
        rows: List<PartyExpenseRow>,
        n: Int = 10,
    ): List<PartyExpenseRow> = rows.take(n)

    /**
     * Monthly summary: 'paid' ledger entries plus inventory purchases per month —
     * every month of the range is present, zero when quiet (web-parity with
     * `expenseSummaryByMonth`). Personal parties' entries are excluded (ADR-027).
     */
    fun byMonth(
        expenses: List<Expense>,
        purchases: List<InventoryTransaction>,
        range: ReportDateRange,
        zone: ZoneId,
        personalPartyIds: Set<String> = emptySet(),
    ): List<ExpenseMonth> {
        val ledgerByMonth =
            expenses
                .filter {
                    it.deletedAt == null &&
                        it.direction == ExpenseDirection.PAID &&
                        it.expenseDate in range.start..range.end &&
                        it.partyId !in personalPartyIds
                }.groupBy { YearMonth.from(it.expenseDate) }
                .mapValues { (_, group) -> group.sumOf { it.amountPaise } }
        val inventoryByMonth = inventoryPurchasesByMonth(purchases, range, zone)
        return range.months().map { month ->
            ExpenseMonth(
                month = month,
                ledgerPaise = ledgerByMonth[month] ?: 0L,
                inventoryPaise = inventoryByMonth[month] ?: 0L,
            )
        }
    }
}

/**
 * §4.4 #7 — cash-basis profit: payments received minus net expenses and inventory
 * purchases, per month. Personal parties' entries are excluded (ADR-027).
 */
object ProfitCalculator {
    fun calculate(
        payments: List<BookingPayment>,
        expenses: List<Expense>,
        purchases: List<InventoryTransaction>,
        range: ReportDateRange,
        zone: ZoneId,
        personalPartyIds: Set<String> = emptySet(),
    ): List<ProfitMonth> {
        val incomeByMonth =
            payments
                .filter { it.deletedAt == null && it.paidOn in range.start..range.end }
                .groupBy { YearMonth.from(it.paidOn) }
                .mapValues { (_, group) -> group.sumOf { it.amountPaise } }
        val expenseByMonth =
            expenses
                .filter { it.deletedAt == null && it.expenseDate in range.start..range.end && it.partyId !in personalPartyIds }
                .groupBy { YearMonth.from(it.expenseDate) }
                .mapValues { (_, group) ->
                    group.sumOf { if (it.direction == ExpenseDirection.PAID) it.amountPaise else -it.amountPaise }
                }
        val inventoryByMonth = inventoryPurchasesByMonth(purchases, range, zone)
        return range.months().map { month ->
            ProfitMonth(
                month = month,
                incomePaise = incomeByMonth[month] ?: 0L,
                expensePaise = (expenseByMonth[month] ?: 0L) + (inventoryByMonth[month] ?: 0L),
            )
        }
    }
}

/**
 * ADR-027 — Personal-expenses report: net spend (paid − received) per personal
 * (non-business-related) party per month, month ascending then biggest spend first.
 * The exact complement of the ExpenseSummary/Profit exclusion, so no entry is ever
 * dropped from both sides.
 */
object PersonalExpensesCalculator {
    fun calculate(
        expenses: List<Expense>,
        personalPartyIds: Set<String>,
        partyNames: Map<String, String>,
        unknownPartyName: String,
        range: ReportDateRange,
    ): List<PersonalExpenseRow> =
        expenses
            .filter { it.deletedAt == null && it.partyId in personalPartyIds && it.expenseDate in range.start..range.end }
            .groupBy { YearMonth.from(it.expenseDate) to it.partyId }
            .map { (key, group) ->
                val (month, partyId) = key
                PersonalExpenseRow(
                    month = month,
                    partyId = partyId,
                    partyName = partyNames[partyId] ?: unknownPartyName,
                    netPaise = group.sumOf { if (it.direction == ExpenseDirection.PAID) it.amountPaise else -it.amountPaise },
                )
            }.sortedWith(compareBy<PersonalExpenseRow> { it.month }.thenByDescending { it.netPaise })
}

/** §4.4 #8 — current FIFO stock value per item, biggest holdings first. */
object InventoryValuationCalculator {
    fun calculate(lines: List<CurrentInventoryLine>): List<ValuationRow> =
        lines
            .filter { it.currentQuantity > 0.0 || it.totalValuePaise > 0L }
            .map {
                ValuationRow(
                    masterItemId = it.masterItemId,
                    name = it.name,
                    unit = it.unit,
                    quantity = it.currentQuantity,
                    valuePaise = it.totalValuePaise,
                )
            }.sortedByDescending { it.valuePaise }
}

/** §4.4 #9 — average days from event end to the payment that completed the total. */
object CollectionEfficiencyCalculator {
    fun calculate(
        bookings: List<Booking>,
        payments: List<BookingPayment>,
    ): CollectionResult {
        val paymentsByBooking = payments.filter { it.deletedAt == null }.groupBy { it.bookingId }
        val entries =
            bookings
                .countable()
                .filter { it.totalAmountPaise > 0L }
                .mapNotNull { booking ->
                    val fullyPaidOn = fullyPaidDate(booking, paymentsByBooking[booking.id].orEmpty()) ?: return@mapNotNull null
                    CollectionEntry(
                        booking = booking,
                        fullyPaidOn = fullyPaidOn,
                        daysToFullPayment = ChronoUnit.DAYS.between(booking.endDate, fullyPaidOn).coerceAtLeast(0L),
                    )
                }.sortedByDescending { it.daysToFullPayment }
        val monthly =
            entries
                .groupBy { YearMonth.from(it.booking.endDate) }
                .map { (month, group) -> CollectionMonth(month, group.map { it.daysToFullPayment }.average()) }
                .sortedBy { it.month }
        val average = entries.takeIf { it.isNotEmpty() }?.map { it.daysToFullPayment }?.average()
        return CollectionResult(averageDays = average, entries = entries, monthly = monthly)
    }

    /** The paid-on date of the payment that made the cumulative total reach the booking amount. */
    private fun fullyPaidDate(
        booking: Booking,
        payments: List<BookingPayment>,
    ): LocalDate? {
        var cumulative = 0L
        payments.sortedWith(compareBy({ it.paidOn }, { it.createdAt })).forEach { payment ->
            cumulative += payment.amountPaise
            if (cumulative >= booking.totalAmountPaise) return payment.paidOn
        }
        return null
    }
}
