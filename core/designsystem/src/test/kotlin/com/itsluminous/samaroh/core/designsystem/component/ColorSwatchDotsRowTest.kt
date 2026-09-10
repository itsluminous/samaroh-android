package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compact colour-dot row (ADR-079): a SINGLE horizontally scrollable line of small
 * dots that must keep ≥48dp touch targets, radio-style selection semantics and the
 * Default(null-key) option of the grid variant.
 */
@RunWith(RobolectricTestRunner::class)
class ColorSwatchDotsRowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun entries(count: Int = 16) =
        (1..count).map { index ->
            ColorSwatchEntry(
                key = "color-$index",
                fill = Color(0xFF000000L or (index * 0x101010L)),
                onFill = Color.White,
                name = "Colour $index",
            )
        }

    private fun setRow(
        selectedKey: String? = null,
        onSelect: (String?) -> Unit = {},
    ) {
        compose.setContent {
            // Narrower than 17 x 48dp targets — forces horizontal overflow.
            Box(Modifier.width(240.dp)) {
                ColorSwatchDotsRow(
                    entries = entries(),
                    selectedKey = selectedKey,
                    defaultSwatchName = "Default",
                    onSelect = onSelect,
                )
            }
        }
    }

    @Test
    fun `row is a single horizontally scrollable line when dots overflow`() {
        setRow()
        compose
            .onNodeWithTag(COLOR_DOTS_ROW_TEST_TAG)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        compose
            .onNodeWithTag(COLOR_DOTS_ROW_TEST_TAG)
            .assert(
                SemanticsMatcher("content overflows (scroll range > 0)") { node ->
                    val range = node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                    range != null && range.maxValue() > 0f
                },
            )
    }

    @Test
    fun `every dot keeps a 48dp touch target around the small visual dot`() {
        setRow()
        compose.onNodeWithContentDescription("Colour 1").assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Colour 1").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Default").assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun `tapping a dot reports its key and the default dot reports null`() {
        val selections = mutableListOf<String?>()
        setRow(onSelect = { selections += it })
        compose.onNodeWithContentDescription("Colour 2").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Default").performScrollTo().performClick()
        assertThat(selections).containsExactly("color-2", null).inOrder()
    }

    @Test
    fun `the selected dot carries radio-style selected semantics`() {
        setRow(selectedKey = "color-3")
        compose.onNodeWithContentDescription("Colour 3").performScrollTo().assertIsSelected()
    }
}
