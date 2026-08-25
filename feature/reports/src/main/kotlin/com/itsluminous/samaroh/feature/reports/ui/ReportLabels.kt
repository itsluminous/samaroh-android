package com.itsluminous.samaroh.feature.reports.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.feature.reports.domain.AgingBucket
import com.itsluminous.samaroh.feature.reports.domain.ReportType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Localized display name of each report (also the exported PDF heading). */
@StringRes
fun ReportType.titleRes(): Int =
    when (this) {
        ReportType.REVENUE -> R.string.reports_report_revenue
        ReportType.DUES_AGING -> R.string.reports_report_dues_aging
        ReportType.OCCUPANCY -> R.string.reports_report_occupancy
        ReportType.EVENT_TYPES -> R.string.reports_report_event_types
        ReportType.SOURCES -> R.string.reports_report_sources
        ReportType.EXPENSE_SUMMARY -> R.string.reports_report_expense_summary
        ReportType.PROFIT -> R.string.reports_report_profit
        ReportType.INVENTORY_VALUATION -> R.string.reports_report_inventory_valuation
        ReportType.COLLECTION -> R.string.reports_report_collection
    }

/** Localized one-line description shown under each report row on the Reports home. */
@StringRes
fun ReportType.subtitleRes(): Int =
    when (this) {
        ReportType.REVENUE -> R.string.reports_report_revenue_subtitle
        ReportType.DUES_AGING -> R.string.reports_report_dues_aging_subtitle
        ReportType.OCCUPANCY -> R.string.reports_report_occupancy_subtitle
        ReportType.EVENT_TYPES -> R.string.reports_report_event_types_subtitle
        ReportType.SOURCES -> R.string.reports_report_sources_subtitle
        ReportType.EXPENSE_SUMMARY -> R.string.reports_report_expense_summary_subtitle
        ReportType.PROFIT -> R.string.reports_report_profit_subtitle
        ReportType.INVENTORY_VALUATION -> R.string.reports_report_inventory_valuation_subtitle
        ReportType.COLLECTION -> R.string.reports_report_collection_subtitle
    }

@StringRes
fun AgingBucket.labelRes(): Int =
    when (this) {
        AgingBucket.DAYS_0_7 -> R.string.reports_aging_bucket_0_7
        AgingBucket.DAYS_8_30 -> R.string.reports_aging_bucket_8_30
        AgingBucket.DAYS_31_90 -> R.string.reports_aging_bucket_31_90
        AgingBucket.DAYS_90_PLUS -> R.string.reports_aging_bucket_90_plus
    }

/**
 * Localized label of a stored `event_type` value: built-in keys (shared
 * event-types.json) resolve through the string catalog; custom labels pass through.
 */
@Composable
fun eventTypeLabel(eventType: String): String =
    when (eventType) {
        "engagement" -> stringResource(R.string.booking_event_type_engagement)
        "tilak" -> stringResource(R.string.booking_event_type_tilak)
        "wedding" -> stringResource(R.string.booking_event_type_wedding)
        "room_booking" -> stringResource(R.string.booking_event_type_room_booking)
        "birthday" -> stringResource(R.string.booking_event_type_birthday)
        "anniversary" -> stringResource(R.string.booking_event_type_anniversary)
        else -> eventType
    }

/** Localized label of a booking source; null renders the "not set" bucket. */
@Composable
fun sourceLabel(source: BookingSource?): String =
    when (source) {
        BookingSource.WALK_IN -> stringResource(R.string.booking_source_walk_in)
        BookingSource.PHONE -> stringResource(R.string.booking_source_phone)
        BookingSource.REFERRAL -> stringResource(R.string.booking_source_referral)
        BookingSource.REPEAT -> stringResource(R.string.booking_source_repeat)
        BookingSource.OTHER -> stringResource(R.string.booking_source_other)
        null -> stringResource(R.string.reports_sources_unspecified)
    }

/** The app-locale of the current composition (per-app locales, §5). */
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** Short month label for chart axes ("Jan" / "जन॰"). */
fun monthAxisLabel(
    month: YearMonth,
    locale: Locale,
): String = month.format(DateTimeFormatter.ofPattern("MMM", locale))

/** Full month label for table rows and selection details ("Jan 2026"). */
fun monthFullLabel(
    month: YearMonth,
    locale: Locale,
): String = month.format(DateTimeFormatter.ofPattern("MMM yyyy", locale))

/** Localized medium date for table rows. */
fun dateLabel(
    date: LocalDate,
    locale: Locale,
): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

/** Inventory quantity display: up to 3 decimals (numeric(10,3) parity), trailing zeros trimmed. */
fun formatQuantity(quantity: Double): String =
    BigDecimal(quantity)
        .setScale(3, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
