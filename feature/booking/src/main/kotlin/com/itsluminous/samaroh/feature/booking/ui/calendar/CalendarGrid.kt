package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.component.CalendarDayCrossfade
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.feature.booking.domain.BookingColorCatalog
import com.itsluminous.samaroh.feature.booking.domain.CalendarMonthMapper
import com.itsluminous.samaroh.feature.booking.ui.fill
import com.itsluminous.samaroh.feature.booking.ui.formatFullDate
import com.itsluminous.samaroh.feature.booking.ui.onFill
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The month grid (§4.1): the date number renders on EVERY cell; booked cells add the
 * event icon(s) as a translucent watermark behind it plus status treatment,
 * grey-striped blocked dates, today outline. Booked cells carry the whole story —
 * firm (confirmed/completed) dates get a filled tertiary-container background,
 * tentative dates an amber outline; no labels or bars render below the date row
 * (customer names live in the agenda list and the TalkBack announcement only).
 */
@Composable
internal fun CalendarGrid(
    grid: CalendarMonthMapper.MonthGrid,
    locale: Locale,
    onDayTapped: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    iconWatermarkAlpha: Float = DataStoreBookingCalendarPrefs.DEFAULT_ICON_WATERMARK_ALPHA,
    bookingColors: BookingColorCatalog? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        WeekdayHeader(locale)
        grid.weeks.forEach { week ->
            WeekRow(week, onDayTapped, iconWatermarkAlpha, bookingColors)
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
    iconWatermarkAlpha: Float,
    bookingColors: BookingColorCatalog?,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        week.days.forEach { day ->
            DayCell(
                day = day,
                onTapped = onDayTapped,
                iconWatermarkAlpha = iconWatermarkAlpha,
                bookingColors = bookingColors,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayCell(
    day: CalendarMonthMapper.Day,
    onTapped: (LocalDate) -> Unit,
    iconWatermarkAlpha: Float,
    bookingColors: BookingColorCatalog?,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.primary
    val stripe = MaterialTheme.colorScheme.outline
    val firmContainer = MaterialTheme.colorScheme.tertiaryContainer
    val tentative = SamarohTheme.semanticColors.tentative
    val shape = MaterialTheme.shapes.small
    // Booking colour (ADR-030): a single firm coloured booking paints the cell's fill;
    // the palette's on_hex keeps the date number legible on it. Multi-booking days and
    // unknown keys fall back to the default tertiary-container fill.
    val customFill = bookingColors?.byKey(day.fillColorKey)
    val fillColor = customFill?.fill
    val onFillColor = customFill?.onFill
    // TalkBack announcement (§6): full date + today/blocked state + booking summary
    // (customer names), so the calendar is navigable cell by cell without sight.
    val description =
        buildList {
            add(formatFullDate(day.date))
            if (day.isToday) add(stringResource(R.string.booking_calendar_a11y_today))
            if (day.isBlocked) add(stringResource(R.string.booking_calendar_a11y_blocked_day))
            if (day.bookingNames.isEmpty()) {
                add(stringResource(R.string.booking_calendar_a11y_no_bookings))
            } else {
                add(
                    pluralStringResource(
                        R.plurals.booking_calendar_a11y_bookings_on_day,
                        day.bookingNames.size,
                        day.bookingNames.size,
                        day.bookingNames.joinToString(),
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
                .clip(shape)
                .then(
                    // Status treatment ON the cell (the old bars' visual vocabulary):
                    // any firm booking → filled container (the booking's own colour when
                    // exactly one coloured firm booking covers the day, ADR-030); any
                    // tentative booking → amber outline. A mixed date carries both.
                    if (day.hasFirmBooking && day.inMonth) {
                        Modifier.background(fillColor ?: firmContainer)
                    } else {
                        Modifier
                    },
                ).then(
                    if (day.isBlocked && day.inMonth) {
                        Modifier.drawBehind { drawStripes(stripe) }
                    } else {
                        Modifier
                    },
                ).then(
                    if (day.hasTentativeBooking && day.inMonth) {
                        Modifier.border(1.dp, tentative, shape)
                    } else {
                        Modifier
                    },
                ).then(
                    if (day.isToday) {
                        Modifier.border(2.dp, outline, shape)
                    } else {
                        Modifier
                    },
                ).clickable(enabled = day.inMonth) { onTapped(day.date) }
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val booked = day.eventIcons.isNotEmpty() && day.inMonth
        if (booked) {
            // Booked date: the event icon(s) render as a TRANSLUCENT WATERMARK behind
            // the date number, so the date stays scannable on every cell. Up to
            // [MAX_DAY_CELL_ICONS] icons fit a cell; more collapse into ONE icon
            // plus a "+N" overflow so the badge never clips. Modifier.alpha (not text
            // color alpha) because color emoji ignore the text color's alpha channel.
            val shown =
                if (day.eventIcons.size > MAX_DAY_CELL_ICONS) day.eventIcons.take(1) else day.eventIcons
            val overflow = day.eventIcons.size - shown.size
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.alpha(CalendarDayCrossfade.iconAlpha(iconWatermarkAlpha)),
            ) {
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
        }
        // The date number renders on top of the watermark (Box children draw in
        // order). On UNBOOKED cells it is always fully opaque. On BOOKED cells it
        // CROSSFADES with the icon per [CalendarDayCrossfade]: full opacity up to
        // the slider midpoint (the default keeps the original watermark look),
        // then a linear fade so at the far right only the icon shows. TalkBack is
        // unaffected — the cell's contentDescription always announces the date.
        Text(
            text = day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            modifier =
                if (booked) {
                    Modifier.alpha(CalendarDayCrossfade.dateAlpha(iconWatermarkAlpha))
                } else {
                    Modifier
                },
            color =
                when {
                    !day.inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    // Custom fill (ADR-030): the palette's paired on-colour keeps the
                    // date number AA-legible on the booking's own colour.
                    day.hasFirmBooking && onFillColor != null && fillColor != null -> onFillColor
                    day.hasFirmBooking -> MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
        )
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
