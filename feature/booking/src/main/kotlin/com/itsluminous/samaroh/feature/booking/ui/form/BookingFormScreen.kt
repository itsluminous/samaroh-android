package com.itsluminous.samaroh.feature.booking.ui.form

import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.itsluminous.samaroh.core.designsystem.component.AmountText
import com.itsluminous.samaroh.core.designsystem.component.AmountTone
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.feature.booking.ui.calendar.DateField

/**
 * Add/Edit booking form (§4.1): event types from the shared catalog, Confirmed/Tentative
 * status, date range, amounts (advance → first payment row), source chips, non-blocking
 * conflict popup, blocking blocked-dates popup with owner override.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingFormScreen(
    onDone: () -> Unit,
    viewModel: BookingFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    val contactPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@rememberLauncherForActivityResult
            context.contentResolver
                .query(
                    uri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        viewModel.setCustomerPhone(cursor.getString(0).orEmpty())
                        if (viewModel.state.value.customerName
                                .isBlank()
                        ) {
                            viewModel.setCustomerName(cursor.getString(1).orEmpty())
                        }
                    }
                }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.editingId == null) R.string.booking_calendar_add else R.string.booking_form_title_edit,
                        ),
                    )
                },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        explanationRes = R.string.common_action_close,
                        onClick = onDone,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Event type dropdown (built-ins from shared/event-types.json + Custom).
            var typeMenu by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = state.eventType?.let { "${it.emoji} ${stringResource(it.labelRes)}" }.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.booking_form_event_type)) },
                trailingIcon = {
                    ExplainableIcon(
                        icon = Icons.Filled.ArrowDropDown,
                        explanationRes = R.string.booking_form_event_type,
                        onClick = { typeMenu = true },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                state.eventTypes.forEach { type ->
                    DropdownMenuItem(
                        text = { Text("${type.emoji} ${stringResource(type.labelRes)}") },
                        onClick = {
                            viewModel.setEventType(type)
                            typeMenu = false
                        },
                    )
                }
            }
            if (state.eventType?.isCustom == true) {
                OutlinedTextField(
                    value = state.customLabel,
                    onValueChange = viewModel::setCustomLabel,
                    label = { Text(stringResource(R.string.booking_form_custom_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.customEmoji,
                    onValueChange = viewModel::setCustomEmoji,
                    label = { Text(stringResource(R.string.booking_form_custom_emoji)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Status: Confirmed (default) / Tentative ★.
            Text(text = stringResource(R.string.booking_form_status), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.status == BookingStatus.CONFIRMED,
                    onClick = { viewModel.setStatus(BookingStatus.CONFIRMED) },
                    label = { Text(stringResource(R.string.booking_status_confirmed)) },
                )
                FilterChip(
                    selected = state.status == BookingStatus.TENTATIVE,
                    onClick = { viewModel.setStatus(BookingStatus.TENTATIVE) },
                    label = { Text(stringResource(R.string.booking_status_tentative)) },
                )
            }

            OutlinedTextField(
                value = state.customerName,
                onValueChange = viewModel::setCustomerName,
                label = { Text(stringResource(R.string.booking_form_customer_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.customerPhone,
                    onValueChange = viewModel::setCustomerPhone,
                    label = { Text(stringResource(R.string.booking_form_customer_phone)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                ExplainableIcon(
                    icon = Icons.Filled.Contacts,
                    explanationRes = R.string.booking_form_pick_contact,
                    onClick = {
                        runCatching {
                            contactPicker.launch(
                                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                            )
                        }
                    },
                )
            }

            DateField(labelRes = R.string.booking_form_start_date, date = state.startDate, onDateChange = viewModel::setStartDate)
            DateField(labelRes = R.string.booking_form_end_date, date = state.endDate, onDateChange = viewModel::setEndDate)

            OutlinedTextField(
                value = state.totalAmountText,
                onValueChange = viewModel::setTotalAmount,
                label = { Text(stringResource(R.string.booking_form_total_amount)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.securityDepositText,
                onValueChange = viewModel::setSecurityDeposit,
                label = { Text(stringResource(R.string.booking_form_security_deposit)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.editingId == null) {
                OutlinedTextField(
                    value = state.advanceText,
                    onValueChange = viewModel::setAdvance,
                    label = { Text(stringResource(R.string.booking_form_advance)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Due — read-only, auto-calculated, live-updating (§4.1).
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.booking_form_due_auto),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    AmountText(
                        amountPaise = state.duePaise,
                        tone = if (state.duePaise > 0) AmountTone.MONEY_OUT else AmountTone.NEUTRAL,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }

            // ★ Source chips (§4.1).
            Text(text = stringResource(R.string.booking_form_source), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(BookingSource.entries) { source ->
                    FilterChip(
                        selected = state.source == source,
                        onClick = { viewModel.setSource(if (state.source == source) null else source) },
                        label = { Text(sourceLabel(source)) },
                    )
                }
            }

            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::setNotes,
                label = { Text(stringResource(R.string.booking_form_notes)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_action_save))
            }
        }
    }

    when (val blocker = state.blocker) {
        null -> Unit

        FormBlocker.NameRequired ->
            SimpleBlockerDialog(
                message = stringResource(R.string.booking_form_name_required),
                onDismiss = viewModel::dismissBlocker,
            )

        FormBlocker.EndBeforeStart ->
            SimpleBlockerDialog(
                message = stringResource(R.string.booking_form_end_before_start),
                onDismiss = viewModel::dismissBlocker,
            )

        is FormBlocker.Conflict ->
            // ★ Non-blocking conflict popup: warn, never block (§4.1).
            AlertDialog(
                onDismissRequest = viewModel::dismissBlocker,
                title = { Text(stringResource(R.string.booking_form_conflict_title)) },
                text = { Text(pluralStringResource(R.plurals.booking_form_conflict_message, blocker.count, blocker.count)) },
                confirmButton = {
                    TextButton(onClick = viewModel::saveAnyway) {
                        Text(stringResource(R.string.booking_form_conflict_save_anyway))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissBlocker) {
                        Text(stringResource(R.string.booking_form_conflict_go_back))
                    }
                },
            )

        is FormBlocker.BlockedDates ->
            // Blocked dates DO block; owners may override (§4.1).
            AlertDialog(
                onDismissRequest = viewModel::dismissBlocker,
                title = { Text(stringResource(R.string.booking_form_blocked_title)) },
                text = { Text(stringResource(R.string.booking_form_blocked_message)) },
                confirmButton = {
                    if (blocker.canOverride) {
                        TextButton(onClick = viewModel::saveDespiteBlock) {
                            Text(stringResource(R.string.booking_form_blocked_override))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissBlocker) {
                        Text(stringResource(R.string.common_action_close))
                    }
                },
            )
    }
}

@Composable
private fun SimpleBlockerDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
        },
    )
}

@Composable
private fun sourceLabel(source: BookingSource): String =
    stringResource(
        when (source) {
            BookingSource.WALK_IN -> R.string.booking_source_walk_in
            BookingSource.PHONE -> R.string.booking_source_phone
            BookingSource.REFERRAL -> R.string.booking_source_referral
            BookingSource.REPEAT -> R.string.booking_source_repeat
            BookingSource.OTHER -> R.string.booking_source_other
        },
    )
