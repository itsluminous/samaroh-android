package com.itsluminous.samaroh.feature.reports.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.designsystem.component.CHIP_ROW_TEST_TAG
import com.itsluminous.samaroh.feature.reports.domain.RangePreset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-104: the shared quick filter renders the six contract chips in order with catalog
 * labels, reflects the selected preset, routes Custom to the picker, and scrolls instead of
 * wrapping on a narrow phone.
 */
@RunWith(RobolectricTestRunner::class)
class RangeQuickFilterTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val selected = mutableStateOf(RangePreset.DEFAULT)
    private val presetTaps = mutableListOf<RangePreset>()
    private var customTaps = 0

    private fun setFilter(width: Int = 320) {
        compose.setContent {
            Box(Modifier.width(width.dp)) {
                RangeQuickFilter(
                    selected = selected.value,
                    onPreset = { presetTaps += it },
                    onCustomClick = { customTaps++ },
                )
            }
        }
    }

    @Test
    fun `renders the six chips in contract order with their catalog labels`() {
        setFilter(width = 2000)
        val expectedOrder = listOf("minus_12m", "minus_3m", "this_month", "plus_3m", "plus_12m", "custom")
        assertThat(RangePreset.CHIPS.map { it.id }).containsExactlyElementsIn(expectedOrder).inOrder()
        var lastLeft = Float.NEGATIVE_INFINITY
        RangePreset.CHIPS.forEach { preset ->
            val node = compose.onNodeWithTag(rangeChipTag(preset))
            node.assertTextEquals(context.getString(preset.labelRes()))
            val left = node.fetchSemanticsNode().boundsInRoot.left
            assertThat(left).isGreaterThan(lastLeft)
            lastLeft = left
        }
    }

    @Test
    fun `selected preset is the only selected chip and taps route presets vs custom`() {
        setFilter()
        compose.onNodeWithTag(rangeChipTag(RangePreset.MINUS_12M)).assertIsSelected()
        compose.onNodeWithTag(rangeChipTag(RangePreset.THIS_MONTH)).assertIsNotSelected()

        compose.onNodeWithTag(rangeChipTag(RangePreset.PLUS_3M)).performScrollTo().performClick()
        assertThat(presetTaps).containsExactly(RangePreset.PLUS_3M)
        assertThat(customTaps).isEqualTo(0)

        compose.onNodeWithTag(rangeChipTag(RangePreset.CUSTOM)).performScrollTo().performClick()
        assertThat(customTaps).isEqualTo(1)
        assertThat(presetTaps).containsExactly(RangePreset.PLUS_3M) // Custom never reports a preset

        selected.value = RangePreset.PLUS_12M
        compose.waitForIdle()
        compose.onNodeWithTag(rangeChipTag(RangePreset.PLUS_12M)).assertIsSelected()
        compose.onNodeWithTag(rangeChipTag(RangePreset.MINUS_12M)).assertIsNotSelected()
    }

    @Test
    fun `narrow row scrolls instead of wrapping or shrinking`() {
        setFilter(width = 240)
        compose
            .onNodeWithTag(CHIP_ROW_TEST_TAG)
            .assert(
                SemanticsMatcher("horizontally scrollable with overflow") { node ->
                    val range = node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                    range != null && range.maxValue() > 0f
                },
            )
    }
}
