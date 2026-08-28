package com.itsluminous.samaroh.core.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.color.BookingColorsProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The client-side seed template (ADR-032): parses the shared submodule's
 * `event-types.json` and resolves labels in ENGLISH regardless of device locale —
 * matching what server migration 006 seeded for existing businesses.
 */
@RunWith(RobolectricTestRunner::class)
class AssetEventTypeSeedTemplateTest {
    private val template = AssetEventTypeSeedTemplate(ApplicationProvider.getApplicationContext())

    @Test
    fun `seeds the seven built-ins in template order with English labels`() {
        val seeds = template.seeds()
        assertThat(seeds.map { it.label })
            .containsExactly("Engagement", "Tilak", "Wedding", "Room Booking", "Birthday", "Anniversary", "Custom")
            .inOrder()
        assertThat(seeds.map { it.sortOrder }).containsExactly(0, 1, 2, 3, 4, 5, 6).inOrder()
        assertThat(seeds.first { it.label == "Wedding" }.icon).isEqualTo("💒")
    }

    @Test
    fun `every seeded colour resolves in the shared palette`() {
        val palette = BookingColorsProvider(ApplicationProvider.getApplicationContext())
        template.seeds().forEach { seed ->
            assertThat(palette.byKey(seed.color)).isNotNull()
        }
    }

    @Test
    fun `seeded colours mirror the shared contract`() {
        assertThat(template.seeds().associate { it.label to it.color })
            .containsExactlyEntriesIn(
                mapOf(
                    "Engagement" to "flamingo",
                    "Tilak" to "tangerine",
                    "Wedding" to "tomato",
                    "Room Booking" to "blueberry",
                    "Birthday" to "banana",
                    "Anniversary" to "sage",
                    "Custom" to "grape",
                ),
            )
    }
}
