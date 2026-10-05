package com.itsluminous.samaroh.feature.booking.reminders

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Snooze chooser presets (ADR-094) — offered in this order on every reminder style.
 * Pure: [fireAt] turns a preset into the re-fire instant for a given local "now".
 */
enum class SnoozePreset(
    val wire: String,
) {
    TEN_MINUTES("10m"),
    THIRTY_MINUTES("30m"),
    ONE_HOUR("1h"),
    THREE_HOURS("3h"),

    /** Next morning at [SnoozePresets.MORNING] local time — the "tomorrow" option. */
    TOMORROW_MORNING("tomorrow"),
    ;

    companion object {
        fun fromWire(value: String?): SnoozePreset? = entries.firstOrNull { it.wire == value }
    }
}

object SnoozePresets {
    /** Local wall-clock time of the "tomorrow morning" preset. */
    val MORNING: LocalTime = LocalTime.of(9, 0)

    /** When a reminder snoozed with [preset] at [now] should ring again. */
    fun fireAt(
        preset: SnoozePreset,
        now: ZonedDateTime,
    ): Instant =
        when (preset) {
            SnoozePreset.TEN_MINUTES -> now.plus(Duration.ofMinutes(10))
            SnoozePreset.THIRTY_MINUTES -> now.plus(Duration.ofMinutes(30))
            SnoozePreset.ONE_HOUR -> now.plus(Duration.ofHours(1))
            SnoozePreset.THREE_HOURS -> now.plus(Duration.ofHours(3))
            // Always the NEXT calendar day at 09:00 in the user's zone — DST-safe via
            // atZone, and never "later today" even when snoozed before 09:00.
            SnoozePreset.TOMORROW_MORNING ->
                now
                    .toLocalDate()
                    .plusDays(1)
                    .atTime(MORNING)
                    .atZone(now.zone)
        }.toInstant()
}
