package com.itsluminous.samaroh.core.designsystem.component

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icon control with a built-in explanation (§6): **long-press shows a localized toast**
 * describing what the icon means — every icon-only control and status indicator in the
 * app must use this wrapper. [explanationRes] doubles as the content description, so
 * TalkBack users get the same information.
 *
 * The default touch target is 48dp (§6). Dense per-row controls (e.g. the checklist
 * item remove cross) may pass a smaller [targetSize]/[iconSize] pair when the default
 * target makes rows cramped — keep compact targets ≥32dp.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ExplainableIcon(
    icon: ImageVector,
    @StringRes explanationRes: Int,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    targetSize: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
) {
    ExplainableIcon(
        icon = icon,
        explanation = stringResource(explanationRes),
        modifier = modifier,
        tint = tint,
        targetSize = targetSize,
        iconSize = iconSize,
        onClick = onClick,
    )
}

/**
 * [ExplainableIcon] with an ALREADY-RESOLVED [explanation] — for labels carrying a
 * placeholder (`stringResource(R.string.some_key, name)`), which the resource-id overload
 * cannot express. Still a catalog string: callers must resolve it via `stringResource`.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ExplainableIcon(
    icon: ImageVector,
    explanation: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    targetSize: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Box(
        modifier =
            modifier
                .size(targetSize)
                .combinedClickable(
                    role = Role.Button,
                    onClick = { onClick?.invoke() },
                    onLongClick = { Toast.makeText(context, explanation, Toast.LENGTH_SHORT).show() },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = explanation,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
