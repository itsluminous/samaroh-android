package com.itsluminous.samaroh.feature.expenses.addperson

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.ExplainableIcon
import com.itsluminous.samaroh.core.designsystem.component.TypeAheadField
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.expenses.BusinessRelatedPill

/** Add-person screen (§4.2): fuzzy dedupe type-ahead, optional phone + contact picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPersonScreen(
    onBack: () -> Unit,
    onOpenLedger: (partyId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddPersonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val contactErrorText = stringResource(R.string.expenses_add_person_contact_error)
    val existsNoticeTemplate = stringResource(R.string.expenses_add_person_exists_notice)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AddPersonEvent.Created -> onOpenLedger(event.partyId)
                is AddPersonEvent.SteeredToExisting -> {
                    Toast.makeText(context, existsNoticeTemplate.format(event.partyName), Toast.LENGTH_SHORT).show()
                    onOpenLedger(event.partyId)
                }
                AddPersonEvent.ContactPickFailed -> snackbarHostState.showSnackbar(contactErrorText)
            }
        }
    }

    val contactLauncher =
        rememberLauncherForActivityResult(PickPhoneContact) { uri ->
            if (uri != null) {
                val picked = resolvePickedContact(context, uri)
                viewModel.onContactPicked(picked?.name, picked?.phone)
            }
        }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.expenses_home_add_person)) },
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
        // IME handling (§6 UX round): fields scroll; the save button stays pinned.
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding(),
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
            ) {
                TypeAheadField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    suggestions = state.suggestions.map { it.name },
                    onSuggestionSelected = viewModel::onSuggestionSelected,
                    onQueryDebounced = viewModel::onQueryDebounced,
                    label = { Text(stringResource(R.string.expenses_add_person_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.nameError) {
                    Text(
                        text = stringResource(R.string.expenses_add_person_name_required),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.phone,
                        onValueChange = viewModel::onPhoneChange,
                        label = { Text(stringResource(R.string.expenses_add_person_phone_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    ExplainableIcon(
                        icon = Icons.Filled.Contacts,
                        explanationRes = R.string.expenses_add_person_pick_contact,
                        onClick = {
                            // Graceful denial (§4.2): the system phone picker needs no runtime
                            // permission; if no picker exists or nothing comes back, we just
                            // show a message — typing the number stays fully available.
                            try {
                                contactLauncher.launch(Unit)
                            } catch (_: ActivityNotFoundException) {
                                viewModel.onContactPicked(null, null)
                            }
                        },
                    )
                }
                BusinessRelatedPill(
                    businessName = state.businessName,
                    businessRelated = state.businessRelated,
                    onBusinessRelatedChange = viewModel::onBusinessRelatedChange,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
            // Pinned action row — always visible, also above the keyboard.
            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.common_action_save))
            }
        }
    }
}

/** Result of the system phone-number picker. */
data class PickedContact(
    val name: String?,
    val phone: String?,
)

/**
 * Picks a phone number via `ACTION_PICK` on the Phone content type — the system picker
 * grants temporary read access to just the chosen row, so NO contacts permission is
 * required (§6: every permission optional).
 */
object PickPhoneContact : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent = Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = intent?.data
}

/** Reads the picked row's display name and number; null when the pick was cancelled or unreadable. */
private fun resolvePickedContact(
    context: Context,
    uri: Uri?,
): PickedContact? {
    if (uri == null) return null
    val projection =
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
    return runCatching {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) PickedContact(name = cursor.getString(0), phone = cursor.getString(1)) else null
        }
    }.getOrNull()
}
