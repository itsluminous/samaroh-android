package com.itsluminous.samaroh.core.designsystem.component

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Container alpha for Samaroh FABs: translucent enough that list content scrolling
 * underneath stays visible, opaque enough that the icon/label remain legible on busy
 * backgrounds (owner feedback: opaque FABs hid content on every screen).
 */
private const val FAB_CONTAINER_ALPHA = 0.76f

/** Fully opaque border so the FAB keeps a crisp, tappable silhouette over any content. */
private val FabBorderWidth = 2.dp

private val FabMinTouchTarget = 48.dp

/** Material 3 FAB container size — the Surface-backed explainable variant matches it. */
private val FabContainerSize = 56.dp

/**
 * The one sanctioned FAB: semi-transparent primary container (content shows through),
 * opaque primary border, low elevation so the translucency reads correctly.
 * [content] is typically an `Icon` carrying its own localized `contentDescription`.
 *
 * With [explanationRes] set the FAB is EXPLAINABLE (§6, ADR-071): long-press shows the
 * localized toast — the same affordance `ExplainableIcon` gives icon-only controls —
 * so an icon-only FAB never leaves its meaning to guesswork.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SamarohFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes explanationRes: Int? = null,
    content: @Composable () -> Unit,
) {
    val shape: Shape = FloatingActionButtonDefaults.shape
    if (explanationRes == null) {
        FloatingActionButton(
            onClick = onClick,
            modifier =
                modifier
                    .sizeIn(minWidth = FabMinTouchTarget, minHeight = FabMinTouchTarget)
                    .border(width = FabBorderWidth, color = MaterialTheme.colorScheme.primary, shape = shape),
            shape = shape,
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = FAB_CONTAINER_ALPHA),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            elevation = samarohFabElevation(),
            content = content,
        )
    } else {
        // FloatingActionButton has no long-press slot, so the explainable variant is
        // the same Surface + centered-content structure with a combinedClickable.
        val context = LocalContext.current
        val explanation = stringResource(explanationRes)
        Surface(
            modifier =
                modifier
                    .sizeIn(minWidth = FabMinTouchTarget, minHeight = FabMinTouchTarget)
                    .border(width = FabBorderWidth, color = MaterialTheme.colorScheme.primary, shape = shape),
            shape = shape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = FAB_CONTAINER_ALPHA),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 1.dp,
        ) {
            Box(
                modifier =
                    Modifier
                        .defaultMinSize(minWidth = FabContainerSize, minHeight = FabContainerSize)
                        .combinedClickable(
                            role = Role.Button,
                            onClickLabel = explanation,
                            onClick = onClick,
                            onLongClick = { Toast.makeText(context, explanation, Toast.LENGTH_SHORT).show() },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }
}

/**
 * Extended variant of [SamarohFab] with the same translucent-container + opaque-border
 * treatment. [icon] and [text] mirror the Material 3 extended-FAB slots.
 */
@Composable
fun SamarohExtendedFab(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape: Shape = FloatingActionButtonDefaults.extendedFabShape
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = icon,
        text = text,
        modifier =
            modifier
                .sizeIn(minWidth = FabMinTouchTarget, minHeight = FabMinTouchTarget)
                .border(width = FabBorderWidth, color = MaterialTheme.colorScheme.primary, shape = shape),
        shape = shape,
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = FAB_CONTAINER_ALPHA),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        elevation = samarohFabElevation(),
    )
}

/** Reduced elevation: a strong shadow under a translucent container reads as grey mud. */
@Composable
private fun samarohFabElevation() =
    FloatingActionButtonDefaults.elevation(
        defaultElevation = 1.dp,
        pressedElevation = 1.dp,
        focusedElevation = 1.dp,
        hoveredElevation = 2.dp,
    )
