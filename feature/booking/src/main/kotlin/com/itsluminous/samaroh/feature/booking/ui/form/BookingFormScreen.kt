package com.itsluminous.samaroh.feature.booking.ui.form

import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.itsluminous.samaroh.core.designsystem.component.AmountText
import com.itsluminous.samaroh.core.designsystem.component.AmountTone
import com.itsluminous.samaroh.core.designsystem.component.ChipRow
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.feature.booking.domain.TentativeFollowUpPlanner
import com.itsluminous.samaroh.feature.booking.ui.calendar.DateField
import com.itsluminous.samaroh.feature.booking.ui.currentLocale
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Add/Edit booking form (§4.1): event types from the shared catalog, Confirmed/Tentative
 * status (+ follow-up selector for tentative, ADR-020), date range, optional times,
 * amounts (advance → first payment row), optional manual invoice number, source chips,
 * non-blocking conflict popup, blocking blocked-dates popup with owner override.
 * Optional fields (deposit/source/times) are gated by Settings → Booking form fields.
 * The save button is pinned below the scrolling fields and stays visible above the IME.
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
        // IME handling (§6 UX round): the fields scroll under the keyboard while the
        // save row stays pinned and visible (imePadding on the pinned column).
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Event type dropdown (built-ins from shared/event-types.json + Custom).
                // ExposedDropdownMenuBox anchors the menu to the field and opens on a tap
                // anywhere in it, not just the arrow.
                var typeMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = typeMenu,
                    onExpandedChange = { typeMenu = it },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = state.eventType?.let { "${it.emoji} ${stringResource(it.labelRes)}" }.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.booking_form_event_type)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenu) },
                        modifier =
                            Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
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
                ChipRow {
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

                // ★ Tentative follow-up selector (ADR-020): 1/3/7 days + custom.
                if (state.status == BookingStatus.TENTATIVE) {
                    Text(
                        text = stringResource(R.string.booking_form_follow_up_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    ChipRow {
                        TentativeFollowUpPlanner.PRESET_DAYS.forEach { days ->
                            FilterChip(
                                selected = !state.followUpCustom && state.followUpDays == days,
                                onClick = { viewModel.selectFollowUpPreset(days) },
                                label = { Text(pluralStringResource(R.plurals.booking_form_follow_up_days_chip, days, days)) },
                            )
                        }
                        FilterChip(
                            selected = state.followUpCustom,
                            onClick = viewModel::selectFollowUpCustom,
                            label = { Text(stringResource(R.string.booking_form_follow_up_custom)) },
                        )
                    }
                    if (state.followUpCustom) {
                        OutlinedTextField(
                            value = state.followUpCustomText,
                            onValueChange = viewModel::setFollowUpCustomText,
                            label = { Text(stringResource(R.string.booking_form_follow_up_custom_days)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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

                // Optional event times — hideable via Settings → Booking form fields.
                if (state.fieldVisibility.showTimes) {
                    TimeField(labelRes = R.string.booking_form_start_time, time = state.startTime, onTimeChange = viewModel::setStartTime)
                    TimeField(labelRes = R.string.booking_form_end_time, time = state.endTime, onTimeChange = viewModel::setEndTime)
                }

                OutlinedTextField(
                    value = state.totalAmountText,
                    onValueChange = viewModel::setTotalAmount,
                    label = { Text(stringResource(R.string.booking_form_total_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Security deposit — HIDDEN by default (ADR-020); the stored value is
                // preserved on edit even while the field is hidden.
                if (state.fieldVisibility.showSecurityDeposit) {
                    OutlinedTextField(
                        value = state.securityDepositText,
                        onValueChange = viewModel::setSecurityDeposit,
                        label = { Text(stringResource(R.string.booking_form_security_deposit)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.editingId == null) {
                    OutlinedTextField(
                        value = state.advanceText,
                        onValueChange = viewModel::setAdvance,
                        label = { Text(stringResource(R.string.booking_form_advance)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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

                // ★ Manual invoice number (ADR-020): editable only until a number is
                // frozen (manually or by the allocator); unique per business.
                val frozenInvoice = state.frozenInvoiceNumber
                OutlinedTextField(
                    value = frozenInvoice ?: state.invoiceNumberText,
                    onValueChange = viewModel::setInvoiceNumber,
                    readOnly = frozenInvoice != null,
                    label = { Text(stringResource(R.string.booking_form_invoice_number)) },
                    supportingText =
                        if (frozenInvoice == null) {
                            { Text(stringResource(R.string.booking_form_invoice_number_hint)) }
                        } else {
                            null
                        },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ★ Source chips (§4.1) — hideable via Settings → Booking form fields.
                if (state.fieldVisibility.showSource) {
                    Text(text = stringResource(R.string.booking_form_source), style = MaterialTheme.typography.labelLarge)
                    ChipRow {
                        BookingSource.entries.forEach { source ->
                            FilterChip(
                                selected = state.source == source,
                                onClick = { viewModel.setSource(if (state.source == source) null else source) },
                                label = { Text(sourceLabel(source)) },
                            )
                        }
                    }
                }

                // ★ Booking colour (ADR-030): Default + 16-swatch palette grid; drives
                // the calendar cell fill, agenda dots and the booking card. While no
                // explicit colour is chosen, the current type's default swatch shows the
                // EFFECTIVE colour (ADR-031); custom types have none (themed default).
                Text(text = stringResource(R.string.booking_form_color), style = MaterialTheme.typography.labelLarge)
                BookingColorPicker(
                    colors = viewModel.bookingColorsProvider.colors,
                    selectedKey = state.colorKey,
                    onSelect = viewModel::setColor,
                    typeDefaultKey = state.eventType?.let { viewModel.eventTypesProvider.defaultColorKeyFor(it.key) },
                )

                OutlinedTextField(
                    value = state.notes,
                    onValueChange = viewModel::setNotes,
                    label = { Text(stringResource(R.string.booking_form_notes)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Pinned action row — always visible, also above the keyboard.
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
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

        FormBlocker.DuplicateInvoiceNumber ->
            SimpleBlockerDialog(
                message = stringResource(R.string.booking_form_invoice_number_duplicate),
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

/** Clickable read-only time field opening a Material time picker; clearable while set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    labelRes: Int,
    time: LocalTime?,
    onTimeChange: (LocalTime?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(currentLocale())
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = time?.format(formatter).orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(labelRes)) },
            enabled = false,
            colors =
                OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            modifier = Modifier.weight(1f).clickable { open = true },
        )
        if (time != null) {
            ExplainableIcon(
                icon = Icons.Filled.Close,
                explanationRes = R.string.booking_form_clear_time,
                onClick = { onTimeChange(null) },
            )
        }
    }
    if (open) {
        val pickerState = rememberTimePickerState(initialHour = time?.hour ?: 12, initialMinute = time?.minute ?: 0)
        AlertDialog(
            onDismissRequest = { open = false },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(pickerState.hour, pickerState.minute))
                    open = false
                }) { Text(stringResource(R.string.common_action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }
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
