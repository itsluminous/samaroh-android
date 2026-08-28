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
            .containsExactly(
                "engagement",
                "tilak",
                "wedding",
                "room_booking",
                "birthday",
                "anniversary",
                "custom",
                "lagan",
                "muh_dikhayi",
            ).inOrder()
        assertThat(provider.byKey("wedding")!!.emoji).isEqualTo("\uD83D\uDC92")
        assertThat(provider.byKey("custom")!!.isCustom).isTrue()
    }

    @Test
    fun `custom labels pass through labelFor unchanged`() {
        val provider = EventTypesProvider(ApplicationProvider.getApplicationContext())
        assertThat(provider.labelFor("family-function", resolve = { "resolved" })).isEqualTo("family-function")
        assertThat(provider.labelFor("wedding", resolve = { "resolved" })).isEqualTo("resolved")
    }
}
