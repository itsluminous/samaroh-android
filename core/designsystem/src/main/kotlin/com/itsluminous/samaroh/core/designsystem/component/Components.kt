package com.itsluminous.samaroh.core.designsystem.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R

/** Standard elevated content card — consistent shape and inner padding across all tabs. */
@Composable
fun SamarohCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** Semantic tone for a money amount. */
enum class AmountTone {
    /** Neutral — rendered in the current content color. */
    NEUTRAL,

    /** Money received ("You got") — green. */
    MONEY_IN,

    /** Money given / due ("You gave") — red. */
    MONEY_OUT,
}

/**
 * The only sanctioned way to render money: Indian digit grouping via [AmountFormatter]
 * (₹1,06,51,161), semantic coloring per shared/brand/palette.md.
 *
 * [masked] renders ₹••• instead of the value (per-module `view_amounts` permission off,
 * ADR-039) with a localized "Amount hidden" accessibility label; the tone color is
 * dropped so the mask leaks no gave/got signal.
 */
@Composable
fun AmountText(
    amountPaise: Long,
    modifier: Modifier = Modifier,
    tone: AmountTone = AmountTone.NEUTRAL,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    masked: Boolean = false,
) {
    if (masked) {
        val hiddenLabel = stringResource(R.string.auth_permissions_amount_hidden_a11y)
        Text(
            text = AmountFormatter.MASKED,
            style = style,
            modifier = modifier.semantics { contentDescription = hiddenLabel },
        )
        return
    }
    val color =
        when (tone) {
            AmountTone.NEUTRAL -> Color.Unspecified
            AmountTone.MONEY_IN -> SamarohTheme.semanticColors.moneyIn
            AmountTone.MONEY_OUT -> SamarohTheme.semanticColors.moneyOut
        }
    Text(text = AmountFormatter.format(amountPaise), color = color, style = style, modifier = modifier)
}

/** Centered empty-state block with an icon, localized title and localized message. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Inline empty-state block for sections embedded in a larger screen (agenda list,
 * sync-status body): same iconography and guidance as [EmptyState], sized to sit
 * inside scrolling content instead of filling the viewport.
 */
@Composable
fun EmptyStateCompact(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Persistent thin connectivity banner (§4.5): "Offline — changes will sync".
 * Render inside the offline-banner slot when connectivity is lost.
 */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = stringResource(R.string.common_state_offline),
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.common_state_offline_banner),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * App-layer permission gating (§3 layer 2): renders [content] only when [allowed].
 * Wave 0 stub — W1-D wires it to `PermissionGuard`; RLS stays the authoritative layer.
 */
@Composable
fun PermissionGate(
    allowed: Boolean,
    modifier: Modifier = Modifier,
    deniedContent: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        if (allowed) content() else deniedContent()
    }
}

/**
 * Localized placeholder screen for not-yet-implemented destinations. [featureNameRes]
 * is the catalog-generated resource for the feature's display name.
 */
@Composable
fun PlaceholderScreen(
    @StringRes featureNameRes: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = icon,
        title = stringResource(R.string.app_placeholder_title),
        message = stringResource(R.string.app_placeholder_message, stringResource(featureNameRes)),
        modifier = modifier,
    )
}
