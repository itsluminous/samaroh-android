package com.itsluminous.samaroh.feature.booking.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Locale-aware date/label formatting helpers (§5 — never hand-rolled strings). */

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

@Composable
fun formatDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentLocale()))

@Composable
fun formatDateRange(
    start: LocalDate,
    end: LocalDate,
): String = if (start == end) formatDate(start) else "${formatDate(start)} – ${formatDate(end)}"

@Composable
fun formatMonthYear(month: YearMonth): String = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", currentLocale()))

/** Localized display label for a stored `event_type` value (custom labels pass through). */
@Composable
fun eventTypeLabel(
    provider: EventTypeCatalog,
    eventTypeKey: String,
): String {
    val builtIn = provider.byKey(eventTypeKey)
    return if (builtIn != null && !builtIn.isCustom) stringResource(builtIn.labelRes) else eventTypeKey
}
