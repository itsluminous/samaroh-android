package com.itsluminous.samaroh.feature.expenses.domain

/**
 * Parses the plain numeric amount field (§4.2: no calculator keypad — a plain keyboard)
 * into Long paise (ADR-002). Rendering goes through `AmountFormatter` only; this is the
 * inverse direction for user input.
 */
object AmountInput {
    /**
     * "1250" → 125000, "1250.5" → 125050, "1,250.50" → 125050 (separator-tolerant).
     * Returns null for anything that is not a positive rupee amount with at most two
     * decimal places.
     */
    fun parseToPaise(text: String): Long? {
        val cleaned = text.trim().replace(",", "")
        if (cleaned.isEmpty() || !cleaned.matches(PATTERN)) return null
        val parts = cleaned.split('.')
        val rupees = parts[0].toLongOrNull() ?: return null
        val paiseFraction =
            parts
                .getOrNull(1)
                .orEmpty()
                .padEnd(2, '0')
                .toLong()
        val paise = rupees * 100 + paiseFraction
        return paise.takeIf { it > 0 }
    }

    private val PATTERN = Regex("""\d{1,10}(\.\d{1,2})?""")
}
