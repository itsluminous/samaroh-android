package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.data.color.BookingColor
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.core.model.EventTypeKinds

/*
 * Booking colour fallback chain (ADR-031, revised by ADR-032): the palette catalog
 * itself now lives in core:data (`core.data.color`) so the Menu feature's event-type
 * manage screen can share it; this file keeps the booking-specific resolution logic.
 */

/**
 * Preset-backed event-type colour lookup (ADR-032). A booking's `event_type` matches a
 * preset by NORMALIZED label — trimmed, lowercased, spaces→underscores — so legacy
 * bookings that recorded a built-in KEY (`room_booking`) still match the row migration
 * 006 seeded from it (`Room Booking`), and follow the user's recolouring of that preset.
 * Free-text types match only when the user made a preset of the same name.
 */
object EventTypePresets {
    /** Delegates to the shared normalization (core:model, ADR-041) so colour and kind matching never drift. */
    fun normalize(label: String): String = EventTypeKinds.normalize(label)

    /** The preset default colour key for a stored `event_type` value, or null. */
    fun defaultColorKeyFor(
        presets: List<EventType>,
        eventType: String,
    ): String? {
        val wanted = normalize(eventType)
        return presets.firstOrNull { it.deletedAt == null && normalize(it.label) == wanted }?.color
    }
}

/**
 * Booking colour fallback chain, applied everywhere a booking's colour is rendered:
 * explicit `bookings.color` → the matching PRESET's default colour (ADR-032; formerly
 * the static event-types.json colour, ADR-031) → null, the standard themed look. A key
 * that does not resolve in the palette (e.g. written by a future app version) falls
 * THROUGH to the next step instead of blanking the chain. A booking whose type matches
 * no live preset stays themed unless explicitly coloured. Stored data is untouched:
 * `bookings.color` NULL still means "follow the type".
 */
object BookingColorFallback {
    /** The palette key that should paint the booking, or null for the themed default. */
    fun effectiveKey(
        explicitColor: String?,
        eventType: String,
        colors: BookingColorCatalog,
        presets: List<EventType>,
    ): String? =
        explicitColor?.takeIf { colors.byKey(it) != null }
            ?: EventTypePresets.defaultColorKeyFor(presets, eventType)?.takeIf { colors.byKey(it) != null }

    /** Resolved palette entry for [booking], or null for the themed default. */
    fun effectiveColor(
        booking: Booking,
        colors: BookingColorCatalog,
        presets: List<EventType>,
    ): BookingColor? = colors.byKey(effectiveKey(booking.color, booking.eventType, colors, presets))
}

/**
 * Visual treatment of an agenda/events row (month-view agenda list, events view, and
 * the day bottom sheet): the row's BACKGROUND is the booking's resolved colour — the
 * ADR-031/032 fallback chain rendered as a tinted pill (web month-pill parity) instead
 * of the former decorative dot. Status comes FIRST: tentative keeps its distinct
 * subtle amber tint + outline and cancelled stays struck-through neutral — neither is
 * ever colour-tinted, mirroring the month grid's "tentative never colours" rule.
 */
sealed interface AgendaRowLook {
    /** Firm booking with a resolved palette colour: tinted row, `on_hex` text (AA). */
    data class Tinted(
        val color: BookingColor,
    ) : AgendaRowLook

    /** Firm booking with no resolved colour: the standard themed container. */
    data object Themed : AgendaRowLook

    /** Tentative: subtle amber tint + outline, never the booking colour. */
    data object Tentative : AgendaRowLook

    /** Cancelled: struck-through on a neutral container, never tinted. */
    data object Cancelled : AgendaRowLook
}

/** Pure agenda-row appearance resolution, reusing [BookingColorFallback]'s chain. */
object AgendaRowAppearance {
    fun lookFor(
        booking: Booking,
        colors: BookingColorCatalog,
        presets: List<EventType>,
    ): AgendaRowLook =
        when (booking.status) {
            BookingStatus.CANCELLED -> AgendaRowLook.Cancelled
            BookingStatus.TENTATIVE -> AgendaRowLook.Tentative
            else ->
                BookingColorFallback
                    .effectiveColor(booking, colors, presets)
                    ?.let { AgendaRowLook.Tinted(it) }
                    ?: AgendaRowLook.Themed
        }
}
