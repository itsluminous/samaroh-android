package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Quick-filter chip rows must scroll horizontally instead of wrapping/squashing the last
 * chip (the report_profit "C-u-s-t-o-m" regression) — pinned on the shared [ChipRow].
 */
@RunWith(RobolectricTestRunner::class)
class ChipRowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun setOverflowingChips(count: Int = 8) {
        compose.setContent {
            // Much narrower than the chips' summed intrinsic width — forces overflow.
            Box(Modifier.width(200.dp)) {
                ChipRow {
                    repeat(count) { index ->
                        FilterChip(
                            selected = false,
                            onClick = {},
                            label = { Text("Filter option $index") },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `row is horizontally scrollable when chips overflow`() {
        setOverflowingChips()
        compose
            .onNodeWithTag(CHIP_ROW_TEST_TAG)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        compose
            .onNodeWithTag(CHIP_ROW_TEST_TAG)
            .assert(
                SemanticsMatcher("content overflows (scroll range > 0)") { node ->
                    val range = node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                    range != null && range.maxValue() > 0f
                },
            )
    }

    @Test
    fun `every chip stays in the tree and the last one is reachable by scrolling`() {
        setOverflowingChips()
        repeat(8) { index ->
            compose.onNodeWithText("Filter option $index").assertExists()
        }
        compose.onNodeWithText("Filter option 7").performScrollTo().assertIsDisplayed()
    }
}
