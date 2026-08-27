package com.itsluminous.samaroh.core.auth

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.image.ItemImageSource
import com.itsluminous.samaroh.core.data.image.isLocalItemImagePath
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import org.junit.Test

/**
 * ADR-023: `image_path` resolution — local absolute paths (device photos not mirrored
 * yet) stay local files; Storage object paths (the web app's form) become authenticated
 * bucket URLs; storage paths without Supabase credentials degrade to Unavailable.
 */
class StorageItemImageResolverTest {
    private val client =
        createSupabaseClient("https://example.supabase.co", "test-anon-key") {
            install(Auth) {
                // Plain-JVM test: no platform storage, lifecycle hooks or refresh jobs.
                enableLifecycleCallbacks = false
                autoLoadFromStorage = false
                autoSaveToStorage = false
                alwaysAutoRefresh = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Storage) {
                // Plain-JVM test: avoid the platform-settings-backed resumable cache.
                resumable { cache = MemoryResumableCache() }
            }
        }

    @Test
    fun `absolute local path resolves to a local file`() {
        val source = StorageItemImageResolver(client).resolve("/data/user/0/app/files/inventory-images/i.webp")

        assertThat(source).isEqualTo(ItemImageSource.LocalFile("/data/user/0/app/files/inventory-images/i.webp"))
    }

    @Test
    fun `storage object path resolves to the authenticated bucket url with a stable cache key`() {
        val source = StorageItemImageResolver(client).resolve("biz-1/item-1/photo.webp")

        val remote = source as ItemImageSource.RemoteObject
        assertThat(remote.url)
            .isEqualTo("https://example.supabase.co/storage/v1/object/authenticated/inventory-images/biz-1/item-1/photo.webp")
        assertThat(remote.cacheKey).isEqualTo("biz-1/item-1/photo.webp")
        // Signed out in this test — cached photos must still render, so no token is fine.
        assertThat(remote.accessToken).isNull()
    }

    @Test
    fun `storage object path without supabase credentials is unavailable`() {
        val source = StorageItemImageResolver(client = null).resolve("biz-1/item-1/photo.webp")

        assertThat(source).isEqualTo(ItemImageSource.Unavailable)
    }

    @Test
    fun `local path without supabase credentials still resolves locally`() {
        val source = StorageItemImageResolver(client = null).resolve("/local/photo.webp")

        assertThat(source).isEqualTo(ItemImageSource.LocalFile("/local/photo.webp"))
    }

    @Test
    fun `path kinds are classified by their prefix`() {
        assertThat(isLocalItemImagePath("/data/user/0/x.webp")).isTrue()
        assertThat(isLocalItemImagePath("file:///data/x.webp")).isTrue()
        assertThat(isLocalItemImagePath("content://media/1")).isTrue()
        assertThat(isLocalItemImagePath("biz/item/x.webp")).isFalse()
        assertThat(isLocalItemImagePath("8f2c1c1e/item-9/17000.webp")).isFalse()
    }
}
