package com.itsluminous.samaroh.feature.booking.domain

import android.content.Context
import androidx.annotation.StringRes
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.Booking
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** One curated booking colour from `shared/booking-colors.json` (ADR-030). */
data class BookingColor(
    /** Stored in `bookings.color`; NULL on the booking = default themed look. */
    val key: String,
    /** Swatch/fill colour, `#RRGGBB`. */
    val hex: String,
    /** Legible text/icon colour ON [hex] (every pair meets WCAG AA >= 4.5:1). */
    val onHex: String,
    /** Localized colour name, resolved from the generated string catalog. */
    @StringRes val labelRes: Int,
)

/** Source of the booking colour palette; interface so tests can fake it without assets. */
interface BookingColorCatalog {
    val colors: List<BookingColor>

    fun byKey(key: String?): BookingColor? = key?.let { k -> colors.firstOrNull { it.key == k } }
}

/**
 * Booking colour fallback chain (ADR-031), applied everywhere a booking's colour is
 * rendered: explicit `bookings.color` → the event type's default colour
 * (`shared/event-types.json`) → null, the standard themed look. A key that does not
 * resolve in the palette (e.g. written by a future app version) falls THROUGH to the
 * next step instead of blanking the chain. Custom (free-text) event types have no type
 * default — they stay themed unless explicitly coloured. Stored data is untouched:
 * `bookings.color` NULL still means "follow the type".
 */
object BookingColorFallback {
    /** The palette key that should paint the booking, or null for the themed default. */
    fun effectiveKey(
        explicitColor: String?,
        eventTypeKey: String,
        colors: BookingColorCatalog,
        eventTypes: EventTypeCatalog,
    ): String? =
        explicitColor?.takeIf { colors.byKey(it) != null }
            ?: eventTypes.defaultColorKeyFor(eventTypeKey)?.takeIf { colors.byKey(it) != null }

    /** Resolved palette entry for [booking], or null for the themed default. */
    fun effectiveColor(
        booking: Booking,
        colors: BookingColorCatalog,
        eventTypes: EventTypeCatalog,
    ): BookingColor? = colors.byKey(effectiveKey(booking.color, booking.eventType, colors, eventTypes))
}

/**
 * Loads the palette from the shared submodule's `booking-colors.json`, copied into this
 * module's assets at build time — the shared file stays the single source of truth
 * (ADR-030, same pattern as [EventTypesProvider]). Unknown keys parse but are dropped
 * (their label can't resolve), so a booking coloured by a NEWER app version simply
 * renders the default look here instead of crashing.
 */
@Singleton
class BookingColorsProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : BookingColorCatalog {
        @Serializable
        private data class BookingColorsFile(
            val colors: List<Entry>,
        )

        @Serializable
        private data class Entry(
            val key: String,
            val hex: String,
            @SerialName("on_hex") val onHex: String,
            @SerialName("label_key") val labelKey: String,
        )

        private val json = Json { ignoreUnknownKeys = true }

        override val colors: List<BookingColor> by lazy {
            val raw =
                context.assets
                    .open("booking-colors.json")
                    .bufferedReader()
                    .use { it.readText() }
            json
                .decodeFromString(BookingColorsFile.serializer(), raw)
                .colors
                .mapNotNull { entry ->
                    labelResFor(entry.key)?.let { BookingColor(entry.key, entry.hex, entry.onHex, it) }
                }
        }

        private fun labelResFor(key: String): Int? =
            when (key) {
                "tomato" -> R.string.booking_color_tomato
                "flamingo" -> R.string.booking_color_flamingo
                "tangerine" -> R.string.booking_color_tangerine
                "banana" -> R.string.booking_color_banana
                "sage" -> R.string.booking_color_sage
                "basil" -> R.string.booking_color_basil
                "olive" -> R.string.booking_color_olive
                "peacock" -> R.string.booking_color_peacock
                "sky" -> R.string.booking_color_sky
                "blueberry" -> R.string.booking_color_blueberry
                "midnight" -> R.string.booking_color_midnight
                "lavender" -> R.string.booking_color_lavender
                "grape" -> R.string.booking_color_grape
                "fuchsia" -> R.string.booking_color_fuchsia
                "cocoa" -> R.string.booking_color_cocoa
                "graphite" -> R.string.booking_color_graphite
                else -> null
            }
    }
