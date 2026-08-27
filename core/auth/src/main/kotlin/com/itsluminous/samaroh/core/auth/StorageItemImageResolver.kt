package com.itsluminous.samaroh.core.auth

import com.itsluminous.samaroh.core.data.image.INVENTORY_IMAGES_BUCKET
import com.itsluminous.samaroh.core.data.image.ItemImageResolver
import com.itsluminous.samaroh.core.data.image.ItemImageSource
import com.itsluminous.samaroh.core.data.image.isLocalItemImagePath
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.storage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves `master_items.image_path` for display (ADR-023): local absolute paths stay
 * local files; Storage object paths become the bucket's AUTHENTICATED object URL plus the
 * signed-in user's current access token. The authenticated URL is stable per object
 * (unlike a signed URL, whose token changes on every creation and would defeat Coil's
 * disk cache), so offline the cached image renders even with an expired session.
 */
@Singleton
class StorageItemImageResolver
    @Inject
    constructor(
        private val client: SupabaseClient?,
    ) : ItemImageResolver {
        override fun resolve(imagePath: String): ItemImageSource {
            if (isLocalItemImagePath(imagePath)) return ItemImageSource.LocalFile(imagePath)
            val supabase = client ?: return ItemImageSource.Unavailable
            return ItemImageSource.RemoteObject(
                url = supabase.storage.from(INVENTORY_IMAGES_BUCKET).authenticatedUrl(imagePath),
                accessToken = supabase.auth.currentSessionOrNull()?.accessToken,
                cacheKey = imagePath,
            )
        }
    }
