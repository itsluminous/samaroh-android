package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Semantics test tag on the SCROLLING region of every [ScrollableSheetScaffold]. */
const val SHEET_SCROLL_TEST_TAG = "sheet_scroll"

/** Semantics test tag on the pinned FOOTER of every [ScrollableSheetScaffold] (when present). */
const val SHEET_FOOTER_TEST_TAG = "sheet_footer"

/**
 * The one way this app renders a modal bottom sheet (ADR-107).
 *
 * A bare `ModalBottomSheet { Column { … } }` neither scrolls nor grows past the screen: once
 * the content is taller than the viewport (a booking card with long notes, a payment history
 * AND an expenses history) the sheet simply clips, and whatever sits last in the column —
 * the action row — is unreachable. This scaffold fixes that structurally:
 *
 * - [content] renders inside a `verticalScroll` column that takes whatever height is LEFT
 *   after the pinned slots (`weight(1f, fill = false)`): short sheets stay content-height,
 *   tall sheets grow to the full screen and scroll in the middle.
 * - [footer] (typically the primary action row) is pinned BELOW the scrolling region and
 *   therefore always on screen without scrolling; [header] (snackbar host, a title row
 *   with Cancel/Save) is pinned above it. Both sit inside the sheet's own system-bar insets
 *   (the M3 sheet pads for the navigation bar) plus `imePadding` for windows that receive
 *   IME insets.
 * - The sheet opens FULLY expanded (`skipPartiallyExpanded = true`):
 *   the M3 half-expanded stop would leave the pinned footer below the fold, which defeats
 *   the point of pinning it. Nested scrolling is the sheet's own — dragging the scroll region
 *   down from its top collapses/dismisses the sheet; the drag handle always dismisses.
 *
 * [contentPadding] / [verticalArrangement] shape the scrolling column only; pinned slots
 * receive the same horizontal padding so their edges line up with the content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollableSheetScaffold(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = SHEET_HORIZONTAL_PADDING, vertical = 8.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Opens FULLY expanded (see above). The experimental `SheetState` stays internal so that
    // callers need no Material opt-in; nobody has asked for a partially expanded sheet.
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            header?.let { pinned ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SHEET_HORIZONTAL_PADDING), content = pinned)
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .testTag(SHEET_SCROLL_TEST_TAG)
                        .padding(contentPadding),
                verticalArrangement = verticalArrangement,
                content = content,
            )
            footer?.let { pinned ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SHEET_HORIZONTAL_PADDING, vertical = 8.dp)
                            .testTag(SHEET_FOOTER_TEST_TAG),
                    content = pinned,
                )
            }
        }
    }
}

/** Horizontal inset shared by the scrolling region and the pinned slots. */
val SHEET_HORIZONTAL_PADDING = 24.dp
