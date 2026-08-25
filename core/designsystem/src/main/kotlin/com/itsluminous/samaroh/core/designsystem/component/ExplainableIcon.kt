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
import androidx.compose.ui.unit.dp

/**
 * Icon control with a built-in explanation (§6): **long-press shows a localized toast**
 * describing what the icon means — every icon-only control and status indicator in the
 * app must use this wrapper. [explanationRes] doubles as the content description, so
 * TalkBack users get the same information.
 *
 * The touch target is 48dp minimum (§6) regardless of icon size.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ExplainableIcon(
    icon: ImageVector,
    @StringRes explanationRes: Int,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val explanation = stringResource(explanationRes)
    Box(
        modifier =
            modifier
                .size(48.dp)
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
            modifier = Modifier.size(24.dp),
        )
    }
}
