package com.itsluminous.samaroh.feature.booking.domain

import android.content.Context
import androidx.annotation.StringRes
import com.itsluminous.samaroh.core.i18n.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One BUILT-IN event type from `shared/event-types.json` (§4.1). Since ADR-032 the
 * picker reads user-managed presets from Room instead; this catalog remains ONLY to
 * localize the recorded `bookings.event_type` of pre-006 bookings that stored a
 * built-in KEY (`wedding` → "Wedding"/"शादी"). Type-default colours moved to the
 * preset rows (the file's `color` is now just the seed template's value).
 */
data class BuiltInEventType(
    /** The key stored in `bookings.event_type` by pre-006 app versions. */
    val key: String,
    /** The key's canonical emoji; emoji are never localized. */
    val emoji: String,
    /** Localized display name, resolved from the generated string catalog. */
    @StringRes val labelRes: Int,
) {
    val isCustom: Boolean get() = key == CUSTOM_KEY

    companion object {
        /** The literal value a blank custom label stores in `bookings.event_type`. */
        const val CUSTOM_KEY = "custom"
    }
}

/** Source of the built-in event types; interface so tests can fake it without assets. */
interface EventTypeCatalog {
    val eventTypes: List<BuiltInEventType>

    fun byKey(key: String): BuiltInEventType? = eventTypes.firstOrNull { it.key == key }

    /** Localized label for a stored `event_type` value; custom labels pass through. */
    fun labelFor(
        eventTypeKey: String,
        resolve: (Int) -> String,
    ): String {
        val builtIn = byKey(eventTypeKey)
        return if (builtIn != null && !builtIn.isCustom) resolve(builtIn.labelRes) else eventTypeKey
    }
}

/**
 * Loads the built-in event types from the shared submodule's `event-types.json`, copied
 * into this module's assets at build time — the shared file stays the single source of
 * truth. Catalog label keys resolve to generated string resources.
 */
@Singleton
class EventTypesProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : EventTypeCatalog {
        @Serializable
        private data class EventTypesFile(
            @SerialName("event_types") val eventTypes: List<Entry>,
        )

        @Serializable
        private data class Entry(
            val key: String,
            val emoji: String,
            @SerialName("label_key") val labelKey: String,
        )

        private val json = Json { ignoreUnknownKeys = true }

        override val eventTypes: List<BuiltInEventType> by lazy {
            val raw =
                context.assets
                    .open("event-types.json")
                    .bufferedReader()
                    .use { it.readText() }
            json
                .decodeFromString(EventTypesFile.serializer(), raw)
                .eventTypes
                .map { BuiltInEventType(it.key, it.emoji, labelResFor(it.key)) }
        }

        private fun labelResFor(key: String): Int =
            when (key) {
                "engagement" -> R.string.booking_event_type_engagement
                "tilak" -> R.string.booking_event_type_tilak
                "wedding" -> R.string.booking_event_type_wedding
                "room_booking" -> R.string.booking_event_type_room_booking
                "birthday" -> R.string.booking_event_type_birthday
                "anniversary" -> R.string.booking_event_type_anniversary
                else -> R.string.booking_event_type_custom
            }
    }
