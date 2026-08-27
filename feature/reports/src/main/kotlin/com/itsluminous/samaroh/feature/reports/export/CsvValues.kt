package com.itsluminous.samaroh.feature.reports.export

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * Machine-readable CSV cell values (ADR-027): spreadsheet apps must parse amounts as
 * numbers and dates as dates, so the CSV export carries plain unformatted values —
 * no currency symbol, no grouping separators, ISO dates. On-screen and PDF rendering
 * keep the localized `AmountFormatter`/date formats.
 */
object CsvValues {
    /**
     * Long paise → plain decimal rupees with exactly two decimals and no grouping:
     * `123456` → `"1234.56"`, `-50` → `"-0.50"`, `0` → `"0.00"`. Never scientific
     * notation (BigDecimal plain string).
     */
    fun rupees(paise: Long): String =
        BigDecimal
            .valueOf(paise)
            .movePointLeft(2)
            .setScale(2)
            .toPlainString()

    /** ISO-8601 date, unambiguous for spreadsheets: `2026-08-27`. */
    fun date(date: LocalDate): String = date.toString()

    /** ISO-8601 year-month: `2026-08`. */
    fun month(month: YearMonth): String = month.toString()

    /** Plain integer/long count with no locale formatting. */
    fun count(value: Long): String = value.toString()

    fun count(value: Int): String = value.toString()
}
