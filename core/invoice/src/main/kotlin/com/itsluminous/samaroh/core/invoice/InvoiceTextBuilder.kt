package com.itsluminous.samaroh.core.invoice

import android.content.Context
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "share as text" receipt (spec §4.1 output format 2, layout-spec "Text variant"):
 * mirrors the PDF order — business, invoice number, customer, event + dates,
 * total / deposit / paid / due, one line per payment — fully localized, ₹ amounts via
 * [AmountFormatter]. Sent through a plain text share intent for customers who can't
 * open PDFs.
 */
@Singleton
class InvoiceTextBuilder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun build(data: InvoiceData): String {
            val ctx = context.withAppLocale()
            val locale = ctx.displayLocale()
            val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

            fun date(value: LocalDate): String = dateFormat.format(value)

            fun label(res: Int): String = ctx.getString(res)

            val booking = data.booking
            val lines = mutableListOf<String>()
            lines += data.business.name
            lines += "${label(R.string.invoice_title)} · ${label(R.string.invoice_number_label)} ${data.invoiceNumber}"
            lines += "${label(R.string.invoice_issue_date_label)}: ${date(data.issueDate)}"
            lines +=
                buildString {
                    append(label(R.string.invoice_billed_to))
                    append(": ")
                    append(booking.customerName)
                    booking.customerPhone?.let { append(" ($it)") }
                }
            val dates =
                if (booking.startDate == booking.endDate) {
                    date(booking.startDate)
                } else {
                    "${date(booking.startDate)} \u2013 ${date(booking.endDate)}"
                }
            lines += "${label(R.string.invoice_event_label)}: ${booking.eventIcon} " +
                "${EventTypeLabels.label(ctx, booking.eventType)} · $dates"
            lines += "${label(R.string.invoice_total_amount)}: ${AmountFormatter.format(booking.totalAmountPaise)}"
            if (booking.securityDepositPaise > 0) {
                lines += "${label(R.string.invoice_security_deposit)}: ${AmountFormatter.format(booking.securityDepositPaise)}"
            }
            lines += "${label(R.string.invoice_payment_history)}:"
            if (data.payments.isEmpty()) {
                lines += "- ${label(R.string.invoice_no_payments)}"
            } else {
                for (payment in data.payments) {
                    lines += "- ${date(payment.paidOn)} · ${label(payment.method.labelRes())} · " +
                        AmountFormatter.format(payment.amountPaise)
                }
            }
            lines += "${label(R.string.invoice_total_paid)}: ${AmountFormatter.format(data.totalPaidPaise)}"
            lines +=
                if (data.duePaise > 0) {
                    "${label(R.string.invoice_balance_due)}: ${AmountFormatter.format(data.duePaise)}"
                } else {
                    "${label(R.string.invoice_balance_due)}: ${AmountFormatter.format(0)} · ${label(R.string.invoice_fully_paid)}"
                }
            lines += label(R.string.invoice_footer)
            return lines.joinToString("\n")
        }
    }
