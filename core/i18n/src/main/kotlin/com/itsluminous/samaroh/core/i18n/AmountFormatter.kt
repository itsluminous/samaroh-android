package com.itsluminous.samaroh.core.i18n

import kotlin.math.abs

/**
 * Formats money amounts with Indian digit grouping (spec §5), for example
 * `₹1,06,51,161` — the last three digits form one group, every group before it has two.
 *
 * Amounts are represented as `Long` **paise** (minor units) throughout the app
 * (see docs/decisions.md ADR-002); this formatter is the single place that turns them
 * into display strings. Never use `String.format("%d")` for money.
 */
object AmountFormatter {
    private const val RUPEE_SIGN = "\u20B9"

    /**
     * The one sanctioned masked-amount rendering (₹•••): shown wherever a member's
     * per-module `view_amounts` permission is off. Symbol-only by design — no catalog
     * key; screen readers announce `auth.permissions.amount_hidden_a11y` instead.
     */
    const val MASKED: String = RUPEE_SIGN + "\u2022\u2022\u2022"

    /**
     * Formats [amountPaise] as a rupee string.
     *
     * @param amountPaise amount in paise (minor units); may be negative.
     * @param showPaise when true, always renders two decimal places; when false (default)
     *   paise are shown only when non-zero.
     */
    fun format(
        amountPaise: Long,
        showPaise: Boolean = false,
    ): String {
        val negative = amountPaise < 0
        val absolute = abs(amountPaise)
        val rupees = absolute / 100
        val paise = (absolute % 100).toInt()
        val grouped = groupIndian(rupees.toString())
        val fraction =
            when {
                showPaise || paise != 0 -> "." + paise.toString().padStart(2, '0')
                else -> ""
            }
        val sign = if (negative) "-" else ""
        return "$sign$RUPEE_SIGN$grouped$fraction"
    }

    /**
     * Applies Indian grouping to a plain digit string: last 3 digits, then groups of 2.
     * `10651161` → `1,06,51,161`.
     */
    fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val head = digits.dropLast(3)
        val tail = digits.takeLast(3)
        val groups = ArrayDeque<String>()
        var remaining = head
        while (remaining.length > 2) {
            groups.addFirst(remaining.takeLast(2))
            remaining = remaining.dropLast(2)
        }
        groups.addFirst(remaining)
        return groups.joinToString(",") + "," + tail
    }
}
