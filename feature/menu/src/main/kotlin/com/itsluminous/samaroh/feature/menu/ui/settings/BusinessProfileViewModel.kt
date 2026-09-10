package com.itsluminous.samaroh.feature.menu.ui.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.designsystem.imaging.CompressionSpec
import com.itsluminous.samaroh.core.designsystem.imaging.ImageCompression
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.Business
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import javax.inject.Inject

/** Business profile editor (§4.4: name/type/address/logo/owner name, invoice prefix). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BusinessProfileViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        activeBusinessProvider: ActiveBusinessProvider,
        private val businessRepository: BusinessRepository,
        permissionGuard: PermissionGuard,
        private val clock: Clock,
    ) : ViewModel() {
        val business: StateFlow<Business?> =
            activeBusinessProvider.activeBusiness.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /**
         * Owner or `settings.manage_business` (§3) — gates the EDITOR. False renders the
         * profile read-only (no save, no logo picker — hidden, not greyed, ADR-038/080);
         * RLS would reject the push anyway, this stops the doomed local save up front.
         * Null until the first permission emission so the editor never flashes for a
         * viewer (the ADR-038 tab-gating pattern). Signed-out/no-business stays
         * owner-mode editable — the app is fully usable offline.
         */
        val canEdit: StateFlow<Boolean?> =
            activeBusinessProvider.activeBusiness
                .flatMapLatest { active ->
                    if (active == null) {
                        flowOf(true)
                    } else {
                        combine(
                            permissionGuard.isOwner(active.id),
                            permissionGuard.permissions(active.id).map { it.settings.manageBusiness },
                        ) { isOwner, manageBusiness -> isOwner || manageBusiness }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
            if (canEdit.value != true) return
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
         * logo via the shared pipeline at the LOGO level (WebP ≤320px, ADR-050 — parity
         * with the onboarding logo; the old code duplicated the encode inline).
         */
        fun setLogo(image: Bitmap) {
            if (canEdit.value != true) return
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
                            val dir = File(appContext.filesDir, LOGO_DIR).apply { mkdirs() }
                            // Timestamped name: a changed path invalidates path-keyed previews.
                            val target = File(dir, "logo-${current.id}-${System.currentTimeMillis()}.webp")
                            ImageCompression.encodeToFile(squared, CompressionSpec.Logo, target)
                            if (squared !== image) squared.recycle()
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

        private companion object {
            const val LOGO_DIR = "business-logos"
        }
    }
