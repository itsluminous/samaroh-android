package com.itsluminous.samaroh.feature.reports.domain

/*
 * Pure total-row math for the tabular money reports (ADR-027): every money table gets a
 * final TOTAL row on screen and in CSV/PDF exports. Kept out of the composables so the
 * sums are unit-testable over fixture data. All money stays Long paise (ADR-002).
 */
object ReportTotals {
    /** Revenue summary: Σcollected / Σoutstanding / Σtotal. */
    data class RevenueTotal(
        val collectedPaise: Long,
        val outstandingPaise: Long,
    ) {
        val totalPaise: Long get() = collectedPaise + outstandingPaise
    }

    fun revenue(months: List<RevenueMonth>): RevenueTotal =
        RevenueTotal(
            collectedPaise = months.sumOf { it.collectedPaise },
            outstandingPaise = months.sumOf { it.outstandingPaise },
        )

    /** Dues aging: Σdue across all listed bookings. */
    fun agingDuePaise(entries: List<AgingEntry>): Long = entries.sumOf { it.duePaise }

    /** Event-type / booking-source breakdowns: Σbookings and Σrevenue. */
    data class BreakdownTotal(
        val bookings: Int,
        val revenuePaise: Long,
    )

    fun eventTypes(rows: List<EventTypeRow>): BreakdownTotal =
        BreakdownTotal(bookings = rows.sumOf { it.bookings }, revenuePaise = rows.sumOf { it.revenuePaise })

    fun sources(rows: List<SourceRow>): BreakdownTotal =
        BreakdownTotal(bookings = rows.sumOf { it.bookings }, revenuePaise = rows.sumOf { it.revenuePaise })

    /** Expense summary (monthly): Σledger / Σinventory / Σtotal. */
    data class ExpenseTotal(
        val ledgerPaise: Long,
        val inventoryPaise: Long,
    ) {
        val totalPaise: Long get() = ledgerPaise + inventoryPaise
    }

    fun expenses(months: List<ExpenseMonth>): ExpenseTotal =
        ExpenseTotal(
            ledgerPaise = months.sumOf { it.ledgerPaise },
            inventoryPaise = months.sumOf { it.inventoryPaise },
        )

    /** Expense summary spend-by-party table: Σnet spend. */
    fun partySpendPaise(rows: List<PartyExpenseRow>): Long = rows.sumOf { it.spendPaise }

    /** Profit: total income / total expense / net. */
    data class ProfitTotal(
        val incomePaise: Long,
        val expensePaise: Long,
    ) {
        val netPaise: Long get() = incomePaise - expensePaise
    }

    fun profit(months: List<ProfitMonth>): ProfitTotal =
        ProfitTotal(
            incomePaise = months.sumOf { it.incomePaise },
            expensePaise = months.sumOf { it.expensePaise },
        )

    /** Inventory valuation: Σ current FIFO value (quantities have mixed units — never summed). */
    fun valuationPaise(rows: List<ValuationRow>): Long = rows.sumOf { it.valuePaise }

    /** Personal expenses: Σnet spend on personal parties. */
    fun personalExpensesPaise(rows: List<PersonalExpenseRow>): Long = rows.sumOf { it.netPaise }
}
