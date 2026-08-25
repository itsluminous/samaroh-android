package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.feature.booking.domain.CalendarMonthMapper
import com.itsluminous.samaroh.feature.booking.ui.formatFullDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The month grid (§4.1): status-colored pills/spanning bars, grey-striped blocked dates,
 * today outline. Confirmed = filled purple (tertiary-container family), tentative =
 * outlined amber, cancelled = hidden (mapper already filters).
 */
@Composable
internal fun CalendarGrid(
    grid: CalendarMonthMapper.MonthGrid,
    locale: Locale,
    onDayTapped: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        WeekdayHeader(locale)
        grid.weeks.forEach { week ->
            WeekRow(week, onDayTapped)
        }
    }
}

@Composable
private fun WeekdayHeader(locale: Locale) {
    Row(modifier = Modifier.fillMaxWidth()) {
        var day = DayOfWeek.SUNDAY
        repeat(7) {
            Text(
                text = day.getDisplayName(TextStyle.NARROW, locale),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            )
            day = day.plus(1)
        }
    }
}

@Composable
private fun WeekRow(
    week: CalendarMonthMapper.Week,
    onDayTapped: (LocalDate) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            week.days.forEachIndexed { index, day ->
                val summaries = week.segments.filter { index in it.startCol..it.endCol }.map { it.label }
                DayCell(
                    day = day,
                    bookingSummaries = summaries,
                    onTapped = onDayTapped,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        week.segments.forEach { segment ->
            SegmentBar(segment = segment, days = week.days, onDayTapped = onDayTapped)
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun DayCell(
    day: CalendarMonthMapper.Day,
    bookingSummaries: List<String>,
    onTapped: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.primary
    val stripe = MaterialTheme.colorScheme.outline
    // TalkBack announcement (§6): full date + today/blocked state + booking summary,
    // so the calendar is navigable cell by cell without sight.
    val description =
        buildList {
            add(formatFullDate(day.date))
            if (day.isToday) add(stringResource(R.string.booking_calendar_a11y_today))
            if (day.isBlocked) add(stringResource(R.string.booking_calendar_a11y_blocked_day))
            if (bookingSummaries.isEmpty()) {
                add(stringResource(R.string.booking_calendar_a11y_no_bookings))
            } else {
                add(
                    pluralStringResource(
                        R.plurals.booking_calendar_a11y_bookings_on_day,
                        bookingSummaries.size,
                        bookingSummaries.size,
                        bookingSummaries.joinToString(),
                    ),
                )
            }
        }.joinToString()
    Box(
        modifier =
            modifier
                .padding(2.dp)
                // ≥48dp touch target (§6); width comes from the 1/7 column weight.
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .then(
                    if (day.isBlocked && day.inMonth) {
                        Modifier.drawBehind { drawStripes(stripe) }
                    } else {
                        Modifier
                    },
                ).then(
                    if (day.isToday) {
                        Modifier.border(2.dp, outline, MaterialTheme.shapes.small)
                    } else {
                        Modifier
                    },
                ).clickable(enabled = day.inMonth) { onTapped(day.date) }
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (day.eventIcons.isNotEmpty() && day.inMonth) {
            // Booked date: the event icon(s) REPLACE the date number (the a11y
            // description above still carries the full date). Cap at
            // [MAX_DAY_CELL_ICONS] icons; the rest collapse into a "+N" overflow.
            val shown = day.eventIcons.take(MAX_DAY_CELL_ICONS)
            val overflow = day.eventIcons.size - shown.size
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = shown.joinToString(""),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                if (overflow > 0) {
                    Text(
                        text = stringResource(R.string.booking_calendar_icon_overflow, overflow.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Text(
                text = day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (day.inMonth) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
            )
        }
    }
}

/** How many booking icons fit a 1/7-width day cell before collapsing into "+N". */
private const val MAX_DAY_CELL_ICONS = 2

/** Grey diagonal stripes for blocked (maintenance/closure) dates. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStripes(color: Color) {
    val step = 8.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(
            color = color.copy(alpha = 0.35f),
            start = Offset(x, size.height),
            end = Offset(x + size.height, 0f),
            strokeWidth = 2.dp.toPx(),
        )
        x += step
    }
}

/**
 * One booking's bar across a week row. Single-day bookings render as a one-column pill:
 * `{icon} {first name}`; multi-day bookings span columns and chain across weeks.
 */
@Composable
private fun SegmentBar(
    segment: CalendarMonthMapper.Segment,
    days: List<CalendarMonthMapper.Day>,
    onDayTapped: (LocalDate) -> Unit,
) {
    val confirmedContainer = MaterialTheme.colorScheme.tertiaryContainer
    val confirmedContent = MaterialTheme.colorScheme.onTertiaryContainer
    val tentative = SamarohTheme.semanticColors.tentative
    val shape = MaterialTheme.shapes.small
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        if (segment.startCol > 0) Spacer(modifier = Modifier.weight(segment.startCol.toFloat()))
        val width = (segment.endCol - segment.startCol + 1).toFloat()
        Box(
            modifier =
                Modifier
                    .weight(width)
                    .padding(horizontal = 2.dp)
                    .clip(shape)
                    .then(
                        when (segment.status) {
                            BookingStatus.TENTATIVE -> Modifier.border(1.dp, tentative, shape)
                            else -> Modifier.background(confirmedContainer)
                        },
                    ).clickable { onDayTapped(days[segment.startCol].date) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    // Hidden from TalkBack: the day cells already announce every booking
                    // with a ≥48dp target; these thin bars would be duplicate tiny stops.
                    .clearAndSetSemantics {},
        ) {
            Text(
                text = segment.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (segment.status == BookingStatus.TENTATIVE) tentative else confirmedContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (segment.endCol < 6) Spacer(modifier = Modifier.weight((6 - segment.endCol).toFloat()))
    }
}

/** Convenience: booking accent per status, used by agenda rows and the card status chip. */
@Composable
internal fun statusColor(status: BookingStatus): Color =
    when (status) {
        BookingStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
        BookingStatus.TENTATIVE -> SamarohTheme.semanticColors.tentative
        BookingStatus.COMPLETED -> SamarohTheme.semanticColors.moneyIn
        BookingStatus.CANCELLED -> MaterialTheme.colorScheme.outline
    }

@Composable
internal fun statusLabel(status: BookingStatus): String =
    androidx.compose.ui.res.stringResource(
        when (status) {
            BookingStatus.CONFIRMED -> com.itsluminous.samaroh.core.i18n.R.string.booking_status_confirmed
            BookingStatus.TENTATIVE -> com.itsluminous.samaroh.core.i18n.R.string.booking_status_tentative
            BookingStatus.COMPLETED -> com.itsluminous.samaroh.core.i18n.R.string.booking_status_completed
            BookingStatus.CANCELLED -> com.itsluminous.samaroh.core.i18n.R.string.booking_status_cancelled
        },
    )
