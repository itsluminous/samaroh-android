package com.itsluminous.samaroh.ui

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Scale selection for the app-bar business-name title: long names shrink between
 * [TITLE_MIN_FONT_SCALE] and 1× of the title font before ellipsizing (same autoSize
 * pattern as the report money cells).
 */
class AppBarTitleAutoSizeTest {
    @Test
    fun `sp title font shrinks between min scale and full size`() {
        // TopAppBar titles are titleLarge = 22sp; the floor is 65% of that.
        assertThat(titleAutoSize(22.sp))
            .isEqualTo(TextAutoSize.StepBased(minFontSize = 22.sp * TITLE_MIN_FONT_SCALE, maxFontSize = 22.sp))
    }

    @Test
    fun `min scale keeps the title readable`() {
        assertThat(TITLE_MIN_FONT_SCALE).isWithin(0.001f).of(0.65f)
    }

    @Test
    fun `non-sp font sizes opt out of scaling`() {
        // No sp base to scale from — the composable falls back to a plain ellipsized Text.
        assertThat(titleAutoSize(TextUnit.Unspecified)).isNull()
        assertThat(titleAutoSize(1.2.em)).isNull()
    }
}
