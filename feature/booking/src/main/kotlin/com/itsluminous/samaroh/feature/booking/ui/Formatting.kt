package com.itsluminous.samaroh.feature.booking.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.itsluminous.samaroh.core.i18n.FormatLocale
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Locale-aware date/label formatting helpers (§5 — never hand-rolled strings).

/** The FORMATTING locale of the current composition: the app locale, Hinglish → en-IN (ADR-110). */
@Composable
fun currentLocale(): Locale = FormatLocale.of(LocalConfiguration.current.locales[0])

@Composable
fun formatDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentLocale()))

/** Full localized date incl. weekday — used for TalkBack announcements on calendar cells. */
@Composable
fun formatFullDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(currentLocale()))

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
