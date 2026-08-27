package com.itsluminous.samaroh.feature.expenses

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.component.ChipRow
import com.itsluminous.samaroh.core.i18n.R

/**
 * "Associated with {business}?" yes/no pill (ADR-027): YES = business party (counts in
 * money reports, the default), NO = personal party (Personal-expenses report only).
 */
@Composable
fun BusinessRelatedPill(
    businessName: String,
    businessRelated: Boolean,
    onBusinessRelatedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.expenses_add_person_business_question, businessName),
            style = MaterialTheme.typography.bodyLarge,
        )
        ChipRow(modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(
                selected = businessRelated,
                onClick = { onBusinessRelatedChange(true) },
                label = { Text(stringResource(R.string.common_action_yes)) },
            )
            FilterChip(
                selected = !businessRelated,
                onClick = { onBusinessRelatedChange(false) },
                label = { Text(stringResource(R.string.common_action_no)) },
            )
        }
    }
}

/** Subtle "Personal" badge shown on party rows and the ledger header (ADR-027). */
@Composable
fun PersonalPartyTag(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = stringResource(R.string.expenses_party_personal_tag),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
