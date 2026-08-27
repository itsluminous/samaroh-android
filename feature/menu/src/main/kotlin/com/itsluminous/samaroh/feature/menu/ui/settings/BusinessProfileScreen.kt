package com.itsluminous.samaroh.feature.menu.ui.settings

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.samaroh.core.designsystem.component.cropper.SquareImageCropperDialog
import com.itsluminous.samaroh.core.designsystem.component.cropper.loadCropSourceBitmap
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.menu.ui.MenuScreenScaffold
import kotlinx.coroutines.launch

/** Business profile editor (§4.4: name/type/address/logo/owner name, invoice prefix). */
@Composable
fun BusinessProfileScreen(
    onBack: () -> Unit,
    viewModel: BusinessProfileViewModel = hiltViewModel(),
) {
    val business by viewModel.business.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Picker result → decoded source bitmap → interactive square cropper → ViewModel.
    var cropSource by remember { mutableStateOf<Bitmap?>(null) }
    val logoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) scope.launch { cropSource = loadCropSourceBitmap(context, uri) }
        }

    cropSource?.let { source ->
        SquareImageCropperDialog(
            bitmap = source,
            onConfirm = { cropped ->
                viewModel.setLogo(cropped)
                cropSource = null
            },
            onDismiss = { cropSource = null },
        )
    }

    MenuScreenScaffold(
        titleRes = R.string.settings_business_title,
        onBack = onBack,
        messageRes = message,
        onMessageShown = viewModel::onMessageShown,
        scrollable = false,
    ) { base ->
        val current = business ?: return@MenuScreenScaffold

        // remember(current.id): fields reset only when a different business loads.
        var name by remember(current.id) { mutableStateOf(current.name) }
        var type by remember(current.id) { mutableStateOf(current.businessType) }
        var address by remember(current.id) { mutableStateOf(current.address.orEmpty()) }
        var ownerName by remember(current.id) { mutableStateOf(current.ownerName) }
        var invoicePrefix by remember(current.id) { mutableStateOf(current.invoicePrefix) }

        // IME handling (§6 UX round): fields scroll; the save button stays pinned.
        Column(modifier = base.imePadding()) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
            ) {
                ProfileField(value = name, onValueChange = { name = it }, labelRes = R.string.settings_business_name)
                ProfileField(value = type, onValueChange = { type = it }, labelRes = R.string.settings_business_type)
                ProfileField(value = address, onValueChange = { address = it }, labelRes = R.string.settings_business_address)
                ProfileField(value = ownerName, onValueChange = { ownerName = it }, labelRes = R.string.settings_business_owner_name)
                ProfileField(
                    value = invoicePrefix,
                    onValueChange = { invoicePrefix = it },
                    labelRes = R.string.settings_business_invoice_prefix,
                )

                OutlinedButton(
                    onClick = { logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.settings_business_change_logo))
                }
            }

            // Pinned action row — always visible, also above the keyboard.
            Button(
                onClick = { viewModel.save(name, type, address, ownerName, invoicePrefix) },
                enabled = name.isNotBlank() && ownerName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(stringResource(R.string.common_action_save))
            }
        }
    }
}

@Composable
private fun ProfileField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes labelRes: Int,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
