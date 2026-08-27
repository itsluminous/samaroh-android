package com.itsluminous.samaroh.feature.menu.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
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

        /**
         * Stores the square bitmap confirmed in the interactive cropper as the business
         * logo: scaled to ≤[MAX_LOGO_DIMENSION_PX], WebP-compressed into app storage
         * (parity with the onboarding logo pipeline — the old code copied raw bytes).
         */
        fun setLogo(image: Bitmap) {
            val current = business.value ?: return
            viewModelScope.launch {
                val path =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val side = minOf(image.width, image.height)
                            val squared =
                                if (image.width == image.height) {
                                    image
                                } else {
                                    Bitmap.createBitmap(image, (image.width - side) / 2, (image.height - side) / 2, side, side)
                                }
                            val scaled =
                                if (side > MAX_LOGO_DIMENSION_PX) {
                                    Bitmap.createScaledBitmap(squared, MAX_LOGO_DIMENSION_PX, MAX_LOGO_DIMENSION_PX, true)
                                } else {
                                    squared
                                }
                            val dir = File(appContext.filesDir, LOGO_DIR).apply { mkdirs() }
                            // Timestamped name: a changed path invalidates path-keyed previews.
                            val target = File(dir, "logo-${current.id}-${System.currentTimeMillis()}.webp")
                            target.outputStream().use { out -> scaled.compress(webpFormat(), WEBP_QUALITY, out) }
                            // Best-effort cleanup of the previously stored logo file.
                            current.logoPath?.let { old ->
                                val oldFile = File(old)
                                if (oldFile.parentFile?.absolutePath == dir.absolutePath) oldFile.delete()
                            }
                            target.absolutePath
                        }.getOrNull()
                    } ?: return@launch
                businessRepository.saveBusiness(current.copy(logoPath = path, updatedAt = clock.instant()))
                _message.value = R.string.settings_business_saved
            }
        }

        private fun webpFormat(): Bitmap.CompressFormat =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

        private companion object {
            const val LOGO_DIR = "business-logos"
            const val MAX_LOGO_DIMENSION_PX = 320
            const val WEBP_QUALITY = 85
        }
    }
