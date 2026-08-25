package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
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

/**
 * The one sanctioned FAB: semi-transparent primary container (content shows through),
 * opaque primary border, low elevation so the translucency reads correctly.
 * [content] is typically an `Icon` carrying its own localized `contentDescription`.
 */
@Composable
fun SamarohFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape: Shape = FloatingActionButtonDefaults.shape
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
