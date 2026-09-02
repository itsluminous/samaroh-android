package com.itsluminous.samaroh.core.model

/**
 * Normalized-label preset matching + kind resolution (ADR-041), shared by the calendar
 * (feature:booking) and the reports aggregations (feature:reports) — feature modules
 * never depend on each other, so the pure matching lives here. A booking's recorded
 * `event_type` (snapshot text, ADR-032) matches a preset by NORMALIZED label — trimmed,
 * lowercased, spaces→underscores — the exact contract `EventTypePresets` established
 * for the colour fallback, so legacy key-recorded bookings (`room_booking`) match their
 * seeded row (`Room Booking`).
 */
object EventTypeKinds {
    /** The shared normalization: trim, lowercase, spaces→underscores. */
    fun normalize(label: String): String = label.trim().lowercase().replace(' ', '_')

    /** The kind of the LIVE preset matching a stored `event_type` value, or null. */
    fun kindFor(
        presets: List<EventType>,
        eventType: String,
    ): EventTypeKind? {
        val wanted = normalize(eventType)
        return presets.firstOrNull { it.deletedAt == null && normalize(it.label) == wanted }?.kind
    }

    /**
     * Whether a booking's recorded type resolves to a MARKER-kind preset. No matching
     * preset (free-text types, deleted presets) → false: an unmatched booking is
     * always treated as a real booking — never silently demoted to a marker.
     */
    fun isMarker(
        presets: List<EventType>,
        eventType: String,
    ): Boolean = kindFor(presets, eventType) == EventTypeKind.MARKER
}
