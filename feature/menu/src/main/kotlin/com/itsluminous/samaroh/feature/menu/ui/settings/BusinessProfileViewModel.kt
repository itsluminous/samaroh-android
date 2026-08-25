package com.itsluminous.samaroh.feature.menu.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.feature.menu.data.CurrentBusinessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import javax.inject.Inject

/** Business profile editor (§4.4: name/type/address/logo/owner name, invoice prefix). */
@HiltViewModel
class BusinessProfileViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        currentBusinessProvider: CurrentBusinessProvider,
        private val businessRepository: BusinessRepository,
        private val clock: Clock,
    ) : ViewModel() {
        val business: StateFlow<Business?> =
            currentBusinessProvider.currentBusiness.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        private val _message = MutableStateFlow<Int?>(null)
        val message: StateFlow<Int?> = _message.asStateFlow()

        fun onMessageShown() {
            _message.value = null
        }

        fun save(
            name: String,
            businessType: String,
            address: String,
            ownerName: String,
            invoicePrefix: String,
        ) {
            val current = business.value ?: return
            if (name.isBlank() || ownerName.isBlank()) return
            viewModelScope.launch {
                businessRepository.saveBusiness(
                    current.copy(
                        name = name.trim(),
                        businessType = businessType.trim().ifEmpty { current.businessType },
                        address = address.trim().ifEmpty { null },
                        ownerName = ownerName.trim(),
                        invoicePrefix = invoicePrefix.trim().ifEmpty { current.invoicePrefix },
                        updatedAt = clock.instant(),
                    ),
                )
                _message.value = R.string.settings_business_saved
            }
        }

        /** Copies the picked image into app storage and stores its path as the logo. */
        fun setLogo(uri: Uri) {
            val current = business.value ?: return
            viewModelScope.launch {
                val path =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = File(appContext.filesDir, LOGO_DIR).apply { mkdirs() }
                            val target = File(dir, "logo-${current.id}")
                            appContext.contentResolver.openInputStream(uri)?.use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            } ?: return@runCatching null
                            target.absolutePath
                        }.getOrNull()
                    } ?: return@launch
                businessRepository.saveBusiness(current.copy(logoPath = path, updatedAt = clock.instant()))
                _message.value = R.string.settings_business_saved
            }
        }

        private companion object {
            const val LOGO_DIR = "business-logos"
        }
    }
