package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * Shared colour-swatch picker (ADR-030 UI, generalized in ADR-032 so both the booking
 * form and the Menu event-type manage screen use the SAME 16-swatch composable —
 * feature modules never depend on each other).
 */

/** `#RRGGBB` → opaque Compose [Color]; null on malformed input (renders the default look). */
fun parseHexColor(hex: String): Color? {
    val digits = hex.removePrefix("#")
    if (digits.length != 6) return null
    val rgb = digits.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}

/** One selectable palette swatch: key + resolved fill/on colours + localized name. */
data class ColorSwatchEntry(
    val key: String,
    val fill: Color,
    val onFill: Color,
    /** Localized colour name — the swatch's whole accessible identity. */
    val name: String,
)

/**
 * A "Default" swatch (null key — the themed look: muted fill + diagonal slash, the
 * familiar "no colour" vocabulary) followed by the palette swatches, 4 per row. Every
 * swatch is a ≥48dp radio-style target announcing its localized name; the selected one
 * carries a 3dp primary ring plus a check mark in the palette's AA-checked on-colour.
 *
 * [effectiveDefaultKey] (ADR-031): while NO explicit colour is selected, the swatch
 * with this key carries a thinner SECONDARY ring and appends [effectiveDefaultLabel] to
 * its announcement — "this is the colour you'll effectively get while Default is
 * chosen". Pass null to disable (e.g. the event-type manage dialog has no such notion).
 */
@Composable
fun ColorSwatchPicker(
    entries: List<ColorSwatchEntry>,
    selectedKey: String?,
    defaultSwatchName: String,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    effectiveDefaultKey: String? = null,
    effectiveDefaultLabel: String? = null,
) {
    // null first = the Default option; unknown selected keys simply show no ring.
    val all: List<ColorSwatchEntry?> = listOf(null) + entries
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        all.chunked(SWATCHES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { entry ->
                    ColorSwatch(
                        entry = entry,
                        defaultSwatchName = defaultSwatchName,
                        selected = selectedKey == entry?.key,
                        effectiveDefault = selectedKey == null && entry != null && entry.key == effectiveDefaultKey,
                        effectiveDefaultLabel = effectiveDefaultLabel,
                        onSelect = { onSelect(entry?.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    entry: ColorSwatchEntry?,
    defaultSwatchName: String,
    selected: Boolean,
    effectiveDefault: Boolean,
    effectiveDefaultLabel: String?,
    onSelect: () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    val ring = MaterialTheme.colorScheme.primary
    val effectiveRing = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outline
    val fillColor = entry?.fill ?: MaterialTheme.colorScheme.surfaceVariant
    val checkColor = entry?.onFill ?: MaterialTheme.colorScheme.onSurfaceVariant
    val colorName = entry?.name ?: defaultSwatchName
    // The effective-default swatch (ADR-031) announces the follow-the-type semantics.
    val name =
        if (effectiveDefault && effectiveDefaultLabel != null) {
            "$colorName, $effectiveDefaultLabel"
        } else {
            colorName
        }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(48.dp)
                .clip(shape)
                .background(fillColor)
                .then(
                    // The Default swatch reads as "no colour": muted fill + a diagonal
                    // slash, the familiar colour-picker vocabulary for none/reset.
                    if (entry == null) {
                        Modifier
                            .border(1.dp, outline, shape)
                            .drawBehind {
                                drawLine(
                                    color = outline,
                                    start = Offset(size.width * 0.2f, size.height * 0.8f),
                                    end = Offset(size.width * 0.8f, size.height * 0.2f),
                                    strokeWidth = 2.dp.toPx(),
                                )
                            }
                    } else {
                        Modifier
                    },
                ).then(
                    when {
                        selected -> Modifier.border(3.dp, ring, shape)
                        // Effective default (ADR-031): a thinner SECONDARY ring, visually
                        // distinct from the primary selection ring + check.
                        effectiveDefault -> Modifier.border(2.dp, effectiveRing, shape)
                        else -> Modifier
                    },
                ).selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onSelect,
                )
                // The localized colour name is the swatch's whole accessible identity.
                .semantics { contentDescription = name },
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null, // the selectable's selected state announces it
                tint = checkColor,
            )
        }
    }
}

/** 4 per row → the 16 palette swatches form a 4×4 grid below the Default swatch. */
private const val SWATCHES_PER_ROW = 4
