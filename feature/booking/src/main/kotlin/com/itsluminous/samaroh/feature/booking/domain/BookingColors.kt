package com.itsluminous.samaroh.feature.booking.domain

import com.itsluminous.samaroh.core.data.color.BookingColor
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.EventType

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
    fun normalize(label: String): String = label.trim().lowercase().replace(' ', '_')

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
