package com.itsluminous.samaroh.feature.onboarding.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.designsystem.component.cropper.SquareImageCropperDialog
import com.itsluminous.samaroh.core.designsystem.component.cropper.loadCropSourceBitmap
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.onboarding.CreateBusinessForm
import com.itsluminous.samaroh.feature.onboarding.OnboardingUiState
import kotlinx.coroutines.launch

/**
 * §4.0 step 5 — create-business form: name*, type (dropdown with free text), address,
 * owner name*, logo (camera/gallery → square crop → ≤320px WebP). No runtime permission
 * is ever required: the camera path uses the permissionless preview contract and the
 * gallery path uses the system photo picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateBusinessScreen(
    state: OnboardingUiState,
    onFormChange: (CreateBusinessForm) -> Unit,
    onLogoCropped: (android.graphics.Bitmap) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Camera/gallery result → interactive square cropper → ViewModel (→ WebP ≤320px).
    var cropSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) cropSource = bitmap
        }
    val galleryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) scope.launch { cropSource = loadCropSourceBitmap(context, uri) }
        }

    cropSource?.let { source ->
        SquareImageCropperDialog(
            bitmap = source,
            onConfirm = { cropped ->
                onLogoCropped(cropped)
                cropSource = null
            },
            onDismiss = { cropSource = null },
        )
    }

    // IME handling (§6 UX round): fields scroll; the submit button stays pinned.
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_create_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 24.dp),
            )

            OutlinedTextField(
                value = form.name,
                onValueChange = { onFormChange(form.copy(name = it)) },
                label = { Text(stringResource(R.string.onboarding_create_name_label)) },
                isError = state.nameMissing,
                supportingText =
                    if (state.nameMissing) {
                        { Text(stringResource(R.string.onboarding_create_name_required)) }
                    } else {
                        null
                    },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            BusinessTypeField(
                value = form.businessType,
                onValueChange = { onFormChange(form.copy(businessType = it)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            OutlinedTextField(
                value = form.address,
                onValueChange = { onFormChange(form.copy(address = it)) },
                label = { Text(stringResource(R.string.onboarding_create_address_label)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            OutlinedTextField(
                value = form.ownerName,
                onValueChange = { onFormChange(form.copy(ownerName = it)) },
                label = { Text(stringResource(R.string.onboarding_create_owner_label)) },
                isError = state.ownerNameMissing,
                supportingText =
                    if (state.ownerNameMissing) {
                        { Text(stringResource(R.string.onboarding_create_owner_required)) }
                    } else {
                        null
                    },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            Text(
                text = stringResource(R.string.onboarding_create_logo_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                form.logoPath?.let { path ->
                    val logo = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                    logo?.let {
                        Image(
                            bitmap = it,
                            contentDescription = stringResource(R.string.onboarding_create_logo_preview),
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
                OutlinedButton(onClick = { cameraLauncher.launch(null) }) {
                    Text(stringResource(R.string.onboarding_create_logo_camera))
                }
                OutlinedButton(
                    onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                ) {
                    Text(stringResource(R.string.onboarding_create_logo_gallery))
                }
            }

            if (state.createFailed) {
                Text(
                    text = stringResource(R.string.onboarding_create_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        // Pinned action row — always visible, also above the keyboard.
        Button(
            onClick = onSubmit,
            enabled = !state.isBusy,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .defaultMinSize(minHeight = 48.dp),
        ) {
            Text(stringResource(R.string.onboarding_create_submit))
        }
    }
}

/** Business-type dropdown with localized suggestions AND free-text entry (§4.0 step 5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BusinessTypeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val suggestions =
        listOf(
            stringResource(R.string.onboarding_business_type_marriage_hall),
            stringResource(R.string.onboarding_business_type_banquet_hall),
            stringResource(R.string.onboarding_business_type_community_hall),
            stringResource(R.string.onboarding_business_type_guest_house),
            stringResource(R.string.onboarding_business_type_other),
        )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.onboarding_create_type_label)) },
            singleLine = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onValueChange(suggestion)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * §4.0 step 6 — optional Google linking with a PROMINENT "Do it later". Never a
 * blocker: onboarding completes either way and lands on the Booking tab.
 */
@Composable
internal fun LinkGoogleScreen(
    onConnect: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_link_google_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.onboarding_link_google_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 32.dp),
        )
        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        ) {
            Text(stringResource(R.string.onboarding_link_google_connect))
        }
        TextButton(
            onClick = onLater,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).defaultMinSize(minHeight = 48.dp),
        ) {
            Text(stringResource(R.string.onboarding_link_google_later), style = MaterialTheme.typography.titleMedium)
        }
    }
}
