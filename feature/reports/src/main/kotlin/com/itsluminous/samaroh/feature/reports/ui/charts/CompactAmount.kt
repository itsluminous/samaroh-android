package com.itsluminous.samaroh.feature.reports.ui.charts

import androidx.compose.runtime.Composable
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import java.util.Locale
import kotlin.math.abs

/**
 * Compact money for chart axes: `₹1.5L`-style Indian magnitudes. The pure split lives
 * here (unit-testable); the localized suffix templates come from the string catalog via
 * [rememberCompactAmountFormatter].
 */
object CompactAmount {
    enum class Magnitude {
        PLAIN,
        THOUSAND,
        LAKH,
        CRORE,
    }

    data class Compact(
        /** Short scaled number like "1.5" or "12" (one decimal, trailing .0 trimmed). */
        val display: String,
        val magnitude: Magnitude,
        val negative: Boolean,
    )

    private const val CRORE_PAISE = 1_00_00_000_00L
    private const val LAKH_PAISE = 1_00_000_00L
    private const val THOUSAND_PAISE = 1_000_00L

    fun of(amountPaise: Long): Compact {
        val absolute = abs(amountPaise)
        val (scaled, magnitude) =
            when {
                absolute >= CRORE_PAISE -> absolute.toDouble() / CRORE_PAISE to Magnitude.CRORE
                absolute >= LAKH_PAISE -> absolute.toDouble() / LAKH_PAISE to Magnitude.LAKH
                absolute >= THOUSAND_PAISE -> absolute.toDouble() / THOUSAND_PAISE to Magnitude.THOUSAND
                else -> 0.0 to Magnitude.PLAIN
            }
        val display =
            if (magnitude == Magnitude.PLAIN) {
                ""
            } else {
                trimDecimal(scaled)
            }
        return Compact(display = display, magnitude = magnitude, negative = amountPaise < 0)
    }

    /** One decimal place, "12.0" collapsed to "12". Locale-stable (Latin digits). */
    fun trimDecimal(value: Double): String {
        val rounded = String.format(Locale.ROOT, "%.1f", value)
        return rounded.removeSuffix(".0")
    }
}

/** Localized compact ₹ axis formatter: paise → "₹1.5L" (en) / "₹1.5 लाख" (hi). */
@Composable
fun rememberCompactAmountFormatter(): (Long) -> String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return { paise ->
        val compact = CompactAmount.of(paise)
        val sign = if (compact.negative) "-" else ""
        when (compact.magnitude) {
            CompactAmount.Magnitude.PLAIN -> AmountFormatter.format(paise)
            CompactAmount.Magnitude.THOUSAND -> sign + context.getString(R.string.reports_amount_thousand, compact.display)
            CompactAmount.Magnitude.LAKH -> sign + context.getString(R.string.reports_amount_lakh, compact.display)
            CompactAmount.Magnitude.CRORE -> sign + context.getString(R.string.reports_amount_crore, compact.display)
        }
    }
}
