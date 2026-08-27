package com.itsluminous.samaroh.feature.booking.domain

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the built-in event types load from the shared submodule's `event-types.json`
 * (copied into assets at build time) — the dropdown's single source of truth (§4.1).
 */
@RunWith(RobolectricTestRunner::class)
class EventTypesProviderTest {
    @Test
    fun `parses the shared event types asset`() {
        val provider = EventTypesProvider(ApplicationProvider.getApplicationContext())
        assertThat(provider.eventTypes.map { it.key })
            .containsExactly("engagement", "tilak", "wedding", "room_booking", "birthday", "anniversary", "custom")
            .inOrder()
        assertThat(provider.byKey("wedding")!!.emoji).isEqualTo("\uD83D\uDC92")
        assertThat(provider.byKey("custom")!!.isCustom).isTrue()
    }

    @Test
    fun `custom labels pass through labelFor unchanged`() {
        val provider = EventTypesProvider(ApplicationProvider.getApplicationContext())
        assertThat(provider.labelFor("family-function", resolve = { "resolved" })).isEqualTo("family-function")
        assertThat(provider.labelFor("wedding", resolve = { "resolved" })).isEqualTo("resolved")
    }

    @Test
    fun `parses each type's default color key from the shared asset`() {
        val provider = EventTypesProvider(ApplicationProvider.getApplicationContext())
        assertThat(provider.eventTypes.associate { it.key to it.defaultColorKey })
            .containsExactlyEntriesIn(
                mapOf(
                    "engagement" to "flamingo",
                    "tilak" to "tangerine",
                    "wedding" to "tomato",
                    "room_booking" to "blueberry",
                    "birthday" to "banana",
                    "anniversary" to "sage",
                    "custom" to "grape",
                ),
            )
    }

    @Test
    fun `defaultColorKeyFor covers built-ins but never custom or free text`() {
        val provider = EventTypesProvider(ApplicationProvider.getApplicationContext())
        assertThat(provider.defaultColorKeyFor("wedding")).isEqualTo("tomato")
        assertThat(provider.defaultColorKeyFor("engagement")).isEqualTo("flamingo")
        // custom parses its colour but the fallback chain must not use it (ADR-031).
        assertThat(provider.defaultColorKeyFor("custom")).isNull()
        assertThat(provider.defaultColorKeyFor("family-function")).isNull()
    }

    @Test
    fun `every built-in default color resolves in the shared palette`() {
        val types = EventTypesProvider(ApplicationProvider.getApplicationContext())
        val palette = BookingColorsProvider(ApplicationProvider.getApplicationContext())
        types.eventTypes.forEach { type ->
            assertThat(palette.byKey(type.defaultColorKey)).isNotNull()
        }
    }
}
