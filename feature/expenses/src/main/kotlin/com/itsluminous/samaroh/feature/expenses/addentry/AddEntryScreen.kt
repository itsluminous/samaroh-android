package com.itsluminous.samaroh.feature.expenses.addentry

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.feature.expenses.attachments.AttachmentCompressor
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Add-entry screen (§4.2): plain numeric amount, date (default today), notes, attachments. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntryScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val limitText = stringResource(R.string.expenses_entry_attach_limit, MAX_ATTACHMENTS.toString())
    val failedText = stringResource(R.string.expenses_entry_attach_failed)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                AddEntryEvent.Saved -> onDone()
                AddEntryEvent.AttachmentLimitReached -> snackbarHostState.showSnackbar(limitText)
                AddEntryEvent.AttachmentFailed -> snackbarHostState.showSnackbar(failedText)
            }
        }
    }

    var captureFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = captureFile
            captureFile = null
            if (success && file != null) viewModel.onImageCaptured(file) else file?.delete()
        }
    val galleryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            uri?.let {
                val mime = context.contentResolver.getType(it) ?: AttachmentCompressor.MIME_JPEG
                viewModel.onAttachmentPicked(it, mime, it.lastPathSegment ?: "image")
            }
        }
    val pdfLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { viewModel.onAttachmentPicked(it, AttachmentCompressor.MIME_PDF, it.lastPathSegment ?: "document.pdf") }
        }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.direction == ExpenseDirection.PAID) {
                                R.string.expenses_home_you_gave
                            } else {
                                R.string.expenses_home_you_got
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.expenses_a11y_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.amountText,
                onValueChange = viewModel::onAmountChange,
                label = { Text(stringResource(R.string.expenses_entry_amount_label)) },
                isError = state.amountError,
                supportingText =
                    if (state.amountError) {
                        { Text(stringResource(R.string.expenses_entry_amount_error)) }
                    } else {
                        null
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            EntryDateField(date = state.date, onDateChange = viewModel::onDateChange)
            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text(stringResource(R.string.expenses_entry_notes_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.expenses_entry_attach_title), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    val file =
                        File(context.filesDir, "expense_attachments")
                            .apply { mkdirs() }
                            .let { dir -> File(dir, "capture-${System.currentTimeMillis()}.jpg") }
                    captureFile = file
                    cameraLauncher.launch(
                        FileProvider.getUriForFile(context, "${context.packageName}.expenses.fileprovider", file),
                    )
                }) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                    Text(stringResource(R.string.expenses_entry_attach_camera), modifier = Modifier.padding(start = 4.dp))
                }
                FilledTonalButton(onClick = {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                    Text(stringResource(R.string.expenses_entry_attach_gallery), modifier = Modifier.padding(start = 4.dp))
                }
                FilledTonalButton(onClick = { pdfLauncher.launch(arrayOf(AttachmentCompressor.MIME_PDF)) }) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                    Text(stringResource(R.string.expenses_entry_attach_pdf), modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (state.attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.attachments.forEach { staged ->
                        StagedAttachmentThumb(staged = staged, onRemove = { viewModel.removeAttachment(staged) })
                    }
                }
            }
            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.common_action_save))
            }
        }
    }

    if (state.showGooglePrompt) {
        // Prompt-to-link-Google stub (§4.2): linking arrives with Settings (W1-F);
        // attachments stay queued locally with a visible pending badge either way.
        AlertDialog(
            onDismissRequest = viewModel::dismissGooglePrompt,
            title = { Text(stringResource(R.string.expenses_google_prompt_title)) },
            text = { Text(stringResource(R.string.expenses_google_prompt_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissGooglePrompt) {
                    Text(stringResource(R.string.expenses_google_prompt_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissGooglePrompt) {
                    Text(stringResource(R.string.expenses_google_prompt_later))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDateField(
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth().clickable { showPicker = true }) {
        OutlinedTextField(
            value = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(stringResource(R.string.expenses_entry_date_label)) },
            colors =
                androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (showPicker) {
        val pickerState =
            rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text(stringResource(R.string.common_action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun StagedAttachmentThumb(
    staged: StagedAttachment,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(72.dp)) {
        if (staged.mimeType.startsWith("image/")) {
            AsyncImage(
                model = staged.file,
                contentDescription = stringResource(R.string.expenses_ledger_attachment_thumbnail),
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = stringResource(R.string.expenses_ledger_attachment_thumbnail),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
        ExplainableIcon(
            icon = Icons.Filled.Close,
            explanationRes = R.string.expenses_entry_remove_attachment,
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
        )
    }
}
