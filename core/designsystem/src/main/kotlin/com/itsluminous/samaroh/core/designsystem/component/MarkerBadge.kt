package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The ONE way to flag a MARKER-kind event type (ADR-041/044, ADR-096) wherever event
 * types are listed for choice — the booking form's type dropdown and the Settings →
 * Event types list. A small flag glyph plus a short label in a `secondaryContainer`
 * pill, sized to sit after a `bodyLarge` label without growing the row.
 *
 * Callers pass the already-localized [label]
 * (`R.string.booking_event_type_marker_badge`); the component adds no text of its own.
 * The flag is decorative (the label carries the meaning), so it has no content
 * description.
 */
@Composable
fun MarkerBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(start = 4.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Flag,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}
