package com.itsluminous.samaroh.feature.inventory.image

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import com.itsluminous.samaroh.core.data.image.ItemImageResolver
import com.itsluminous.samaroh.core.data.image.ItemImageSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File

/** Hilt seam: composables reach the singleton [ItemImageResolver] without a ViewModel. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ItemImageResolverEntryPoint {
    fun itemImageResolver(): ItemImageResolver
}

/**
 * Coil model for an item photo (ADR-023): a [File] for photos on this device's disk, an
 * authenticated Storage [ImageRequest] for photos in the private `inventory-images`
 * bucket, or null when the photo cannot be shown (no path / Supabase unconfigured) —
 * callers fall back to the placeholder icon.
 */
@Composable
internal fun rememberItemImageModel(imagePath: String?): Any? {
    if (imagePath == null) return null
    val context = LocalContext.current
    val resolver =
        remember(context) {
            EntryPointAccessors
                .fromApplication(context.applicationContext, ItemImageResolverEntryPoint::class.java)
                .itemImageResolver()
        }
    return remember(imagePath) { itemImageModel(context, resolver.resolve(imagePath)) }
}

/** Pure mapping from a resolved [ItemImageSource] to the Coil model (unit-tested). */
internal fun itemImageModel(
    context: Context,
    source: ItemImageSource,
): Any? =
    when (source) {
        is ItemImageSource.LocalFile -> File(source.path)
        is ItemImageSource.RemoteObject ->
            ImageRequest
                .Builder(context)
                .data(source.url)
                // Stable keys: the object path, NOT the (token-bearing) URL — cached
                // photos keep rendering offline and across sign-in refreshes.
                .memoryCacheKey(source.cacheKey)
                .diskCacheKey(source.cacheKey)
                .apply { source.accessToken?.let { addHeader("Authorization", "Bearer $it") } }
                .build()
        ItemImageSource.Unavailable -> null
    }
