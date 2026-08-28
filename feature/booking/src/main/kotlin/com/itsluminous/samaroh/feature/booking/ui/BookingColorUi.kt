package com.itsluminous.samaroh.feature.booking.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.data.color.BookingColor
import com.itsluminous.samaroh.core.designsystem.component.parseHexColor

// Booking colour presentation helpers (ADR-030).

/** Swatch/fill colour of a palette entry. */
val BookingColor.fill: Color? get() = parseHexColor(hex)

/** Legible on-colour of a palette entry (AA-checked in the shared palette). */
val BookingColor.onFill: Color? get() = parseHexColor(onHex)

/**
 * Small colour indicator dot used on agenda/events rows and the booking card. Purely
 * decorative — the row text carries the information — so no semantics of its own.
 */
@Composable
internal fun BookingColorDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 10.dp,
) {
    androidx.compose.foundation.layout
        .Box(
            modifier =
                modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(color),
        )
}
