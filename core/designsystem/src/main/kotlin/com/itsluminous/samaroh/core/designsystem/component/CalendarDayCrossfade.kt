package com.itsluminous.samaroh.core.designsystem.component

/**
 * Date ↔ icon CROSSFADE mapping for booked calendar day cells (owner feedback).
 *
 * One slider value drives BOTH layers of a booked cell:
 *
 * ```
 * slider v:   0.15 ......... 0.45 ...... 0.525 ............ 0.9
 * iconAlpha:  0.15  (=v, rises linearly with the slider)     0.9
 * dateAlpha:  1.0   1.0      1.0   (linear fade)             0.0
 * ```
 *
 * - `iconAlpha(v) = v` — the event-icon watermark simply gets stronger as the
 *   slider moves right (far left = faint icon, far right = fully bold icon).
 * - `dateAlpha(v)` — the date number stays FULLY opaque until the fade start
 *   ([DATE_FADE_START], the midpoint of the slider range), then fades linearly
 *   to 0.0 at [SLIDER_MAX]: at the far right ONLY the icon shows.
 *
 * The default ([SLIDER_DEFAULT]) sits BELOW the fade start, so out of the box the
 * calendar keeps the original watermark look: full-opacity date over a 45% icon.
 * Unbooked cells never fade — the mapping applies to booked cells only.
 */
object CalendarDayCrossfade {
    /** Slider lower bound: the icon is faint but never invisible. */
    const val SLIDER_MIN = 0.15f

    /** Slider upper bound: bold icon, and the date number fully fades out. */
    const val SLIDER_MAX = 0.9f

    /** Default (original watermark look): date fully opaque over a 45% icon. */
    const val SLIDER_DEFAULT = 0.45f

    /**
     * Where the date starts fading: the midpoint of the slider range (0.525).
     * Everything at or left of this keeps the date at full opacity.
     */
    const val DATE_FADE_START = (SLIDER_MIN + SLIDER_MAX) / 2f

    /** Watermark opacity of the event icon(s): the slider value itself, clamped. */
    fun iconAlpha(sliderValue: Float): Float = sliderValue.coerceIn(SLIDER_MIN, SLIDER_MAX)

    /**
     * Opacity of the date number on a BOOKED cell: 1.0 up to [DATE_FADE_START],
     * then linearly down to 0.0 at [SLIDER_MAX].
     */
    fun dateAlpha(sliderValue: Float): Float {
        val v = sliderValue.coerceIn(SLIDER_MIN, SLIDER_MAX)
        if (v <= DATE_FADE_START) return 1f
        return 1f - (v - DATE_FADE_START) / (SLIDER_MAX - DATE_FADE_START)
    }
}
