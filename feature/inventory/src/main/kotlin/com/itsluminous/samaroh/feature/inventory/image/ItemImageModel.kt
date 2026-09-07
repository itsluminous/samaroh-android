package com.itsluminous.samaroh.feature.inventory.image

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.itsluminous.samaroh.core.data.image.ItemImageResolver
import com.itsluminous.samaroh.core.data.image.ItemImageSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the resolver ladder needs to render one item's photo (ADR-063). */
data class ItemPhoto(
    val itemId: String,
    val imagePath: String?,
    val driveImageId: String?,
) {
    /** True when there is anything to even try rendering. */
    val hasAnySource: Boolean get() = imagePath != null || driveImageId != null
}

/** Hilt seam: composables reach the singleton resolver + fetcher without a ViewModel. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ItemImageEntryPoint {
    fun itemImageResolver(): ItemImageResolver

    fun itemPhotoDriveFetcher(): ItemPhotoDriveFetcher
}

/**
 * The rendered model of an item photo (ADR-063 ladder): a device-local [File] — the
 * original, or a Drive download cached on first render — or null while nothing is
 * available (callers fall back to the placeholder icon). Local files resolve in one
 * frame for practical purposes; a Drive-hosted photo not cached yet renders the
 * placeholder, downloads (own token → public link) and then recomposes with the file.
 */
@Composable
internal fun rememberItemImageModel(photo: ItemPhoto?): File? {
    if (photo == null || !photo.hasAnySource) return null
    val context = LocalContext.current
    val entryPoint =
        remember(context) {
            EntryPointAccessors.fromApplication(context.applicationContext, ItemImageEntryPoint::class.java)
        }
    // LaunchedEffect + state (not produceState: its lint detector false-positives on
    // assignments that follow a suspend call). Keyed on the photo, so a replaced image
    // re-resolves and a recycled row never shows the previous item's photo.
    var resolved by remember(photo) { mutableStateOf<File?>(null) }
    LaunchedEffect(photo) {
        resolved =
            resolveItemImageFile(
                photo = photo,
                resolver = entryPoint.itemImageResolver(),
                fetch = { id -> entryPoint.itemPhotoDriveFetcher().fetch(id) },
            )
    }
    return resolved
}

/** Pure-ish resolution used by [rememberItemImageModel] and unit tests. */
internal suspend fun resolveItemImageFile(
    photo: ItemPhoto,
    resolver: ItemImageResolver,
    fetch: suspend (String) -> File?,
): File? =
    withContext(Dispatchers.IO) {
        when (val source = resolver.resolve(photo.itemId, photo.imagePath, photo.driveImageId)) {
            is ItemImageSource.LocalFile -> File(source.path)
            is ItemImageSource.DriveFile -> fetch(source.driveImageId)
            ItemImageSource.Unavailable -> null
        }
    }

/** Non-composable variant for places that already run in a coroutine (viewer download source). */
internal suspend fun resolveItemImageFile(
    context: Context,
    photo: ItemPhoto,
): File? {
    val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, ItemImageEntryPoint::class.java)
    return resolveItemImageFile(photo, entryPoint.itemImageResolver()) { id ->
        entryPoint.itemPhotoDriveFetcher().fetch(id)
    }
}
