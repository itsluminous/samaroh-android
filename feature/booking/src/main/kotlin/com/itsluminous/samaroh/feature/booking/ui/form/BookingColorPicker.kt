package com.itsluminous.samaroh.feature.booking.ui.form

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.booking.domain.BookingColor
import com.itsluminous.samaroh.feature.booking.ui.fill
import com.itsluminous.samaroh.feature.booking.ui.onFill

/**
 * Booking colour picker (ADR-030): a "Default" swatch (null key — the themed calendar
 * look) followed by the 16 shared palette swatches, laid out 4 per row. Every swatch is
 * a ≥48dp radio-style target announcing its localized colour name; the selected one
 * carries a primary ring plus a check mark in the palette's AA-checked on-colour.
 *
 * [typeDefaultKey] is the current event type's default colour (ADR-031). While NO
 * explicit colour is chosen (Default selected), that swatch carries a secondary ring
 * and announces "Default — follows event type" — the EFFECTIVE colour the booking will
 * render with. Tapping any swatch stores an explicit colour; tapping Default stores
 * null (follow the type) — stored semantics are unchanged.
 */
@Composable
internal fun BookingColorPicker(
    colors: List<BookingColor>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    typeDefaultKey: String? = null,
) {
    // null first = the Default option; unknown selected keys simply show no ring.
    val entries: List<BookingColor?> = listOf(null) + colors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.chunked(SWATCHES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { entry ->
                    ColorSwatch(
                        entry = entry,
                        selected = selectedKey == entry?.key,
                        effectiveDefault = selectedKey == null && entry != null && entry.key == typeDefaultKey,
                        onSelect = { onSelect(entry?.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    entry: BookingColor?,
    selected: Boolean,
    effectiveDefault: Boolean,
    onSelect: () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    val ring = MaterialTheme.colorScheme.primary
    val effectiveRing = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outline
    val fillColor = entry?.fill ?: MaterialTheme.colorScheme.surfaceVariant
    val checkColor = entry?.onFill ?: MaterialTheme.colorScheme.onSurfaceVariant
    val colorName = stringResource(entry?.labelRes ?: R.string.booking_color_default)
    // The effective-default swatch (ADR-031) announces the follow-the-type semantics.
    val name =
        if (effectiveDefault) {
            "$colorName, ${stringResource(R.string.booking_color_follows_type)}"
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
                        // distinct from the primary selection ring + check — "this is the
                        // colour you'll get while Default is chosen".
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
