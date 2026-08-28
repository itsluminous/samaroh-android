package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.core.model.displayIcon
import com.itsluminous.samaroh.feature.booking.domain.AgendaRowAppearance
import com.itsluminous.samaroh.feature.booking.domain.AgendaRowLook
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.ui.eventTypeLabel
import com.itsluminous.samaroh.feature.booking.ui.fill
import com.itsluminous.samaroh.feature.booking.ui.formatDateRange
import com.itsluminous.samaroh.feature.booking.ui.onFill

/**
 * One booking row of the agenda lists (month-view agenda, events view) and the day
 * bottom sheet: a rounded pill whose BACKGROUND is the booking's resolved colour
 * (explicit → preset default → themed container, ADR-031/032), with the palette's
 * AA-checked `on_hex` carrying every text on a tinted row. Tentative keeps its
 * distinct subtle amber tint + outline (never the booking colour); cancelled stays
 * struck-through on a neutral container. Replaces the former decorative colour dot —
 * web month-pill parity.
 */
@Composable
internal fun BookingAgendaRow(
    booking: Booking,
    eventTypes: EventTypeCatalog,
    bookingColors: BookingColorCatalog,
    presets: List<EventType>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = AgendaRowAppearance.lookFor(booking, bookingColors, presets)
    val shape = MaterialTheme.shapes.small
    val tentative = SamarohTheme.semanticColors.tentative
    val themedContainer = MaterialTheme.colorScheme.tertiaryContainer
    val onThemedContainer = MaterialTheme.colorScheme.onTertiaryContainer

    val background: Color
    val contentColor: Color
    val statusTextColor: Color
    when (look) {
        is AgendaRowLook.Tinted -> {
            // Unparseable hex degrades to the themed container (same fallback spirit).
            background = look.color.fill ?: themedContainer
            contentColor = look.color.onFill ?: onThemedContainer
            statusTextColor = contentColor
        }

        AgendaRowLook.Themed -> {
            background = themedContainer
            contentColor = onThemedContainer
            statusTextColor = onThemedContainer
        }

        AgendaRowLook.Tentative -> {
            // Subtle tint over the surface: normal text colours stay AA on it.
            background = tentative.copy(alpha = 0.12f)
            contentColor = MaterialTheme.colorScheme.onSurface
            statusTextColor = statusColor(BookingStatus.TENTATIVE)
        }

        AgendaRowLook.Cancelled -> {
            background = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            statusTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    val cancelled = booking.status == BookingStatus.CANCELLED

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(background)
                .then(
                    if (look == AgendaRowLook.Tentative) {
                        Modifier.border(1.dp, tentative, shape)
                    } else {
                        Modifier
                    },
                ).clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${booking.displayIcon} ${eventTypeLabel(eventTypes, booking.eventType)} - ${booking.customerName}",
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            )
            Text(
                text = formatDateRange(booking.startDate, booking.endDate),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
        }
        Text(
            text = statusLabel(booking.status),
            style = MaterialTheme.typography.labelLarge,
            color = statusTextColor,
        )
    }
}
