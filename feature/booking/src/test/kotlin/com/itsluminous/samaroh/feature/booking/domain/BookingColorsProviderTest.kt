package com.itsluminous.samaroh.feature.booking.domain

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the booking colour palette loads from the shared submodule's
 * `booking-colors.json` (copied into assets at build time, ADR-030) — 16 swatches,
 * well-formed hexes and a resolvable localized label for every key.
 */
@RunWith(RobolectricTestRunner::class)
class BookingColorsProviderTest {
    private val provider = BookingColorsProvider(ApplicationProvider.getApplicationContext())

    @Test
    fun `parses all 16 shared palette colors in order`() {
        assertThat(provider.colors.map { it.key })
            .containsExactly(
                "tomato",
                "flamingo",
                "tangerine",
                "banana",
                "sage",
                "basil",
                "olive",
                "peacock",
                "sky",
                "blueberry",
                "midnight",
                "lavender",
                "grape",
                "fuchsia",
                "cocoa",
                "graphite",
            ).inOrder()
    }

    @Test
    fun `every entry carries a well-formed hex pair and a label resource`() {
        val hexPattern = Regex("^#[0-9A-Fa-f]{6}$")
        provider.colors.forEach { color ->
            assertThat(color.hex).matches(hexPattern.pattern)
            assertThat(color.onHex).matches(hexPattern.pattern)
            assertThat(color.labelRes).isNotEqualTo(0)
        }
    }

    @Test
    fun `byKey resolves known keys and returns null for unknown or null`() {
        assertThat(provider.byKey("peacock")!!.hex).isEqualTo("#00838F")
        assertThat(provider.byKey("peacock")!!.onHex).isEqualTo("#FFFFFF")
        assertThat(provider.byKey("not-a-color")).isNull()
        assertThat(provider.byKey(null)).isNull()
    }
}
