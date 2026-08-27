package com.itsluminous.samaroh.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CalendarDayCrossfadeTest {
    // ---- left endpoint: date fully opaque, icon faint ----

    @Test
    fun `far left - icon at minimum, date fully opaque`() {
        assertThat(CalendarDayCrossfade.iconAlpha(CalendarDayCrossfade.SLIDER_MIN)).isEqualTo(0.15f)
        assertThat(CalendarDayCrossfade.dateAlpha(CalendarDayCrossfade.SLIDER_MIN)).isEqualTo(1f)
    }

    // ---- default / middle: original watermark look preserved ----

    @Test
    fun `default keeps the original watermark look - full date over 45 percent icon`() {
        assertThat(CalendarDayCrossfade.iconAlpha(CalendarDayCrossfade.SLIDER_DEFAULT)).isEqualTo(0.45f)
        assertThat(CalendarDayCrossfade.dateAlpha(CalendarDayCrossfade.SLIDER_DEFAULT)).isEqualTo(1f)
    }

    @Test
    fun `date stays fully opaque up to the fade start`() {
        assertThat(CalendarDayCrossfade.DATE_FADE_START).isEqualTo(0.525f)
        assertThat(CalendarDayCrossfade.dateAlpha(CalendarDayCrossfade.DATE_FADE_START)).isEqualTo(1f)
        assertThat(CalendarDayCrossfade.dateAlpha(0.5f)).isEqualTo(1f)
    }

    @Test
    fun `date fades linearly past the fade start`() {
        // Halfway through the fade zone (0.525..0.9) → date at 50%.
        val midFade = (CalendarDayCrossfade.DATE_FADE_START + CalendarDayCrossfade.SLIDER_MAX) / 2f
        assertThat(CalendarDayCrossfade.dateAlpha(midFade)).isWithin(1e-6f).of(0.5f)
        // Icon keeps tracking the slider value in the fade zone.
        assertThat(CalendarDayCrossfade.iconAlpha(midFade)).isEqualTo(midFade)
    }

    // ---- right endpoint: only the icon shows ----

    @Test
    fun `far right - icon at maximum, date fully invisible`() {
        assertThat(CalendarDayCrossfade.iconAlpha(CalendarDayCrossfade.SLIDER_MAX)).isEqualTo(0.9f)
        assertThat(CalendarDayCrossfade.dateAlpha(CalendarDayCrossfade.SLIDER_MAX)).isEqualTo(0f)
    }

    // ---- clamping ----

    @Test
    fun `out-of-range slider values clamp to the bounds`() {
        assertThat(CalendarDayCrossfade.iconAlpha(0f)).isEqualTo(CalendarDayCrossfade.SLIDER_MIN)
        assertThat(CalendarDayCrossfade.dateAlpha(0f)).isEqualTo(1f)
        assertThat(CalendarDayCrossfade.iconAlpha(2f)).isEqualTo(CalendarDayCrossfade.SLIDER_MAX)
        assertThat(CalendarDayCrossfade.dateAlpha(2f)).isEqualTo(0f)
    }
}
