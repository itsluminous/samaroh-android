package com.itsluminous.samaroh.core.i18n

import android.content.Context
import android.content.res.Configuration
import java.time.LocalDate
import java.util.Locale

/**
 * The EVENT PARTY name (ADR-106, shared `docs/expense-against-event.md` §2.1):
 *
 * ```
 * {EventTypeLabel} - {dd-MMM-yyyy} - {CustomerName}
 * ```
 *
 * e.g. `Wedding - 20-Oct-2026 - Sandhya Upadhyay`. The name is STORED data every device of
 * the business reads (like the seeded preset labels, ADR-032 §3), so it is built in
 * ENGLISH ALWAYS — never from the device locale, never with a localized month or a
 * Devanagari digit — otherwise two phones would mint two parties for one event. Both
 * apps build it identically; the web mirrors this exact algorithm.
 */
object EventPartyName {
    /** ` - ` — space, hyphen-minus (U+002D), space. */
    const val SEPARATOR = " - "

    /** English three-letter month abbreviations, initial capital, locale-independent. */
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val whitespace = Regex("\\s+")

    /**
     * Builds the name from an already-resolved [eventTypeLabel] (see [eventTypeLabel]),
     * the booking's [startDate] and its [customerName] (trimmed, whitespace runs collapsed).
     */
    fun build(
        eventTypeLabel: String,
        startDate: LocalDate,
        customerName: String,
    ): String = eventTypeLabel.trim() + SEPARATOR + formatDate(startDate) + SEPARATOR + collapse(customerName)

    /**
     * Builds the name for a stored booking: [eventType] is `bookings.event_type` as stored
     * (legacy built-in KEYS resolve to their ENGLISH catalog label via [context]).
     */
    fun build(
        context: Context,
        eventType: String,
        startDate: LocalDate,
        customerName: String,
    ): String = build(eventTypeLabel(context, eventType), startDate, customerName)

    /**
     * `{dd-MMM-yyyy}`: two-digit day, English month abbreviation, four-digit year — built
     * from integers, not a locale-aware formatter, so `Locale.getDefault()` is irrelevant.
     */
    fun formatDate(date: LocalDate): String =
        date.dayOfMonth.toString().padStart(2, '0') + "-" + months[date.monthValue - 1] + "-" + date.year.toString().padStart(4, '0')

    /**
     * `{EventTypeLabel}`: the stored `bookings.event_type` trimmed as stored — it is the
     * preset's label snapshot (user data; a Hindi custom label stays Hindi). EXCEPTION: a
     * legacy built-in KEY from `event-types.json` (`wedding`, `room_booking`, …; matched by
     * the shared `normalize` rule — trim, lowercase, spaces → underscores) resolves to that
     * preset's ENGLISH catalog label. Never the emoji.
     */
    fun eventTypeLabel(
        context: Context,
        eventType: String,
    ): String = eventTypeLabel(eventType) { res -> englishContext(context).getString(res) }

    /** Pure variant of [eventTypeLabel]: [resolveEnglish] resolves a catalog resource in English. */
    fun eventTypeLabel(
        eventType: String,
        resolveEnglish: (Int) -> String,
    ): String {
        val stored = eventType.trim()
        val builtInRes = EventTypeLabels.builtInRes(stored.lowercase(Locale.ROOT).replace(' ', '_'))
        return builtInRes?.let(resolveEnglish) ?: stored
    }

    private fun collapse(customerName: String): String = customerName.trim().replace(whitespace, " ")

    private fun englishContext(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.ENGLISH)
        return context.createConfigurationContext(configuration)
    }
}
