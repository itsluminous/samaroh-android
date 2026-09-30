package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * The app-wide "shrink to fit" floor: auto-sized text never drops below this fraction of
 * its style's font size (0.6 keeps 16sp body at ≥ 9.6sp — still legible on a 360dp phone
 * with long Hindi labels). Below the floor the text ellipsizes ([TextOverflow.Ellipsis])
 * or clips, per caller.
 */
const val AUTO_SHRINK_MIN_FONT_SCALE = 0.6f

/**
 * Single-line text that SHRINKS its font to fit the available width instead of wrapping
 * (the reports-table money-cell convention, ADR-091, promoted to the design system).
 * Compose `BasicText` step-based auto-size between `style.fontSize × [minFontScale]` and
 * `style.fontSize`; the content color is resolved like material `Text` does. Use it for
 * amounts and short labels inside fixed-width cells (summary cards, table columns,
 * segmented buttons) — never for body copy, which should wrap.
 */
@Composable
fun AutoShrinkText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    minFontScale: Float = AUTO_SHRINK_MIN_FONT_SCALE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val resolvedColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val resolved = style.merge(TextStyle(color = resolvedColor, fontWeight = fontWeight, textAlign = textAlign ?: style.textAlign))
    BasicText(
        text = text,
        style = resolved,
        maxLines = 1,
        softWrap = false,
        overflow = overflow,
        autoSize =
            TextAutoSize.StepBased(
                minFontSize = style.fontSize * minFontScale,
                maxFontSize = style.fontSize,
            ),
        modifier = modifier,
    )
}
