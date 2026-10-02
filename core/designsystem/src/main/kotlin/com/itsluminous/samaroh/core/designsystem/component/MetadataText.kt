package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme

/**
 * The ONE way to render a metadata line (ADR-093): provenance and attribution text that is
 * ABOUT a record rather than part of it — "Added by Priya on 2 Oct 2026", "Updated 30 Sep".
 *
 * Renders in [SamarohTheme.metadataTextStyle] (labelSmall size, monospace face) and
 * [SamarohTheme.metadataColor] (`outline`), so it is visibly secondary to any body copy
 * sitting next to it: smaller, quieter and in a different face. The record's own content
 * (notes, names, amounts, dates that ARE the record) must never use this style — it stays
 * `bodyMedium`/`onSurface`. Callers pass an already-localized string; this component adds
 * no text of its own.
 */
@Composable
fun MetadataText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text = text,
        style = SamarohTheme.metadataTextStyle,
        color = SamarohTheme.metadataColor,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}
