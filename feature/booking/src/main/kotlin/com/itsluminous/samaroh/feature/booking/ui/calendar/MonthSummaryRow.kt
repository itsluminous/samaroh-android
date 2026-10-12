package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.component.AutoShrinkText
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme

/** Test tags of the two summary cells. */
internal const val SUMMARY_RECEIVED_TAG = "booking_summary_received"
internal const val SUMMARY_PENDING_TAG = "booking_summary_pending"

/**
 * The month summary line of the Booking tab (§4.1): `Received ₹X` (moneyIn) and
 * `Pending ₹Y` (moneyOut) side by side. ADR-103: each cell takes half the width and its
 * text SHRINKS to fit on ONE line (`AutoShrinkText`, floor `AUTO_SHRINK_MIN_FONT_SCALE`)
 * instead of wrapping the card taller or pushing the pending cell off-screen — long Hindi
 * labels and lakh-scale amounts on a 360dp phone were doing both. Below the floor the
 * text ellipsizes.
 */
@Composable
internal fun MonthSummaryRow(
    receivedText: String,
    pendingText: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AutoShrinkText(
            text = receivedText,
            style = MaterialTheme.typography.titleMedium,
            color = SamarohTheme.semanticColors.moneyIn,
            modifier = Modifier.weight(1f).testTag(SUMMARY_RECEIVED_TAG),
        )
        AutoShrinkText(
            text = pendingText,
            style = MaterialTheme.typography.titleMedium,
            color = SamarohTheme.semanticColors.moneyOut,
            modifier = Modifier.weight(1f).testTag(SUMMARY_PENDING_TAG),
        )
    }
}
