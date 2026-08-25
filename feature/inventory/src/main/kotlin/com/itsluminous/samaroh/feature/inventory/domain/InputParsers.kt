package com.itsluminous.samaroh.feature.inventory.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/*
 * Form-input parsing and display helpers. Money is Long paise end to end (ADR-002):
 * the user types rupees, storage and math use paise, rendering goes through
 * AmountFormatter/AmountText only.
 */

private val QUANTITY_PATTERN = Regex("^\\d{1,7}(\\.\\d{1,3})?$")
private val RUPEE_PATTERN = Regex("^\\d{1,10}(\\.\\d{1,2})?$")

/** Parses a quantity input (up to 3 decimals, numeric(10,3) parity); null when invalid or zero. */
fun parseQuantity(text: String): Double? {
    val trimmed = text.trim()
    if (!QUANTITY_PATTERN.matches(trimmed)) return null
    val value = trimmed.toDouble()
    return if (value > 0) value else null
}

/** Parses a rupee amount ("120", "120.5", "120.50") into Long paise; null when invalid. */
fun parseRupeesToPaise(text: String): Long? {
    val trimmed = text.trim()
    if (!RUPEE_PATTERN.matches(trimmed)) return null
    val parts = trimmed.split('.')
    val rupees = parts[0].toLong()
    val paise = if (parts.size > 1) parts[1].padEnd(2, '0').toLong() else 0L
    return rupees * 100 + paise
}

/** Formats a quantity for display, trimming trailing zeros (7.0 → "7", 2.5 → "2.5"). */
fun formatQuantity(value: Double): String {
    val symbols = DecimalFormatSymbols(Locale.getDefault())
    return DecimalFormat("0.###", symbols).format(value)
}
