package com.itsluminous.samaroh.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.itsluminous.samaroh.core.data.share.ShareAction
import com.itsluminous.samaroh.core.i18n.R

/** Why the chooser has nothing to offer (design D18 messages). */
enum class ShareBlocked {
    SIGNED_OUT,
    NO_OPTIONS,
    UNSUPPORTED,
}

/**
 * The unified share chooser (ADR-086): "What would you like to do?" with the rows the
 * payload + permissions allow — Create invoice / Set as item photo / Save to Files.
 * Rows are permission-hidden (never greyed); with none left the dialog explains why.
 */
@Composable
fun ShareChooserDialog(
    actions: List<ShareAction>,
    blocked: ShareBlocked?,
    onPick: (ShareAction) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_share_target_chooser_title)) },
        text = {
            if (blocked != null || actions.isEmpty()) {
                Text(
                    stringResource(
                        when (blocked ?: ShareBlocked.NO_OPTIONS) {
                            ShareBlocked.SIGNED_OUT -> R.string.files_share_target_signed_out
                            ShareBlocked.NO_OPTIONS -> R.string.files_share_target_no_options
                            ShareBlocked.UNSUPPORTED -> R.string.files_share_target_unsupported
                        },
                    ),
                )
            } else {
                Column {
                    actions.forEach { action ->
                        when (action) {
                            ShareAction.CREATE_INVOICE ->
                                ChooserRow(
                                    icon = Icons.Filled.ReceiptLong,
                                    title = stringResource(R.string.expenses_share_target_label),
                                    subtitle = stringResource(R.string.files_share_target_option_invoice_subtitle),
                                    onClick = { onPick(action) },
                                )
                            ShareAction.SET_ITEM_PHOTO ->
                                ChooserRow(
                                    icon = Icons.Filled.Inventory2,
                                    title = stringResource(R.string.files_share_target_option_inventory),
                                    subtitle = stringResource(R.string.files_share_target_option_inventory_subtitle),
                                    onClick = { onPick(action) },
                                )
                            ShareAction.SAVE_TO_FILES ->
                                ChooserRow(
                                    icon = Icons.Filled.Folder,
                                    title = stringResource(R.string.files_share_target_option_files),
                                    subtitle = stringResource(R.string.files_share_target_option_files_subtitle),
                                    onClick = { onPick(action) },
                                )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

@Composable
private fun ChooserRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
