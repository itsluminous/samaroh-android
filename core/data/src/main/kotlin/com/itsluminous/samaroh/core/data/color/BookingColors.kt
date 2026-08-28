package com.itsluminous.samaroh.core.data.color

import android.content.Context
import androidx.annotation.StringRes
import com.itsluminous.samaroh.core.i18n.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Booking colour palette (ADR-030), moved here from feature:booking in ADR-032 so the
 * Menu feature's event-type manage screen can share the single palette source with the
 * booking form and calendar (feature modules never depend on each other).
 */

/** One curated booking colour from `shared/booking-colors.json` (ADR-030). */
data class BookingColor(
    /** Stored in `bookings.color` / `event_types.color`; NULL = default themed look. */
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
 * Loads the palette from the shared submodule's `booking-colors.json`, copied into this
 * module's assets at build time — the shared file stays the single source of truth
 * (ADR-030). Unknown keys parse but are dropped (their label can't resolve), so a
 * booking coloured by a NEWER app version simply renders the default look here instead
 * of crashing.
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
