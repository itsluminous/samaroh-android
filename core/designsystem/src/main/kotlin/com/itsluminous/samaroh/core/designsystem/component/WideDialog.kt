package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A chooser/picker dialog that uses the FULL available width (ADR-090): the platform
 * default `AlertDialog` width leaves list rows cramped on compact phones and forces long
 * labels to wrap. Renders a Material surface at ~92% of the screen width (capped for
 * tablets), a `titleLarge` heading, a caller-supplied body (typically a scrollable list
 * of compact `body`-sized rows) and an actions row — the same shell for the share
 * chooser, the folder pickers and any future list-style dialog.
 *
 * Body text inside should use `bodyMedium`/`bodyLarge`, never title styles.
 */
@Composable
fun WideDialog(
    title: String,
    onDismissRequest: () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier =
                modifier
                    .widthIn(max = MAX_WIDTH)
                    .fillMaxWidth(WIDTH_FRACTION),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Column(modifier = Modifier.heightIn(max = screenHeight * BODY_MAX_HEIGHT_FRACTION), content = body)
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { actions() }
            }
        }
    }
}

private const val WIDTH_FRACTION = 0.92f
private const val BODY_MAX_HEIGHT_FRACTION = 0.6f
private val MAX_WIDTH = 560.dp
