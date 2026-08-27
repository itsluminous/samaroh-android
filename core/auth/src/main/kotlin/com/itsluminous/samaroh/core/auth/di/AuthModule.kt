package com.itsluminous.samaroh.core.auth.di

import com.itsluminous.samaroh.core.auth.AuthConfig
import com.itsluminous.samaroh.core.auth.AuthRepository
import com.itsluminous.samaroh.core.auth.DefaultPermissionGuard
import com.itsluminous.samaroh.core.auth.MembershipRefresher
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.auth.SessionActiveBusinessProvider
import com.itsluminous.samaroh.core.auth.SessionCurrentUserProvider
import com.itsluminous.samaroh.core.auth.SessionHolder
import com.itsluminous.samaroh.core.auth.StorageItemImageResolver
import com.itsluminous.samaroh.core.auth.SupabaseAuthManager
import com.itsluminous.samaroh.core.auth.SupabaseMembershipRefresher
import com.itsluminous.samaroh.core.data.image.ItemImageResolver
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    @Binds abstract fun bindSessionHolder(impl: SupabaseAuthManager): SessionHolder

    @Binds abstract fun bindAuthRepository(impl: SupabaseAuthManager): AuthRepository

    @Binds abstract fun bindPermissionGuard(impl: DefaultPermissionGuard): PermissionGuard

    @Binds abstract fun bindMembershipRefresher(impl: SupabaseMembershipRefresher): MembershipRefresher

    /** Wave-1 session seam (docs/decisions.md ADR-017). */
    @Binds abstract fun bindActiveBusinessProvider(impl: SessionActiveBusinessProvider): ActiveBusinessProvider

    @Binds abstract fun bindCurrentUserProvider(impl: SessionCurrentUserProvider): CurrentUserProvider

    /** Item photo display resolution (ADR-023) — rides on the shared authed client. */
    @Binds abstract fun bindItemImageResolver(impl: StorageItemImageResolver): ItemImageResolver

    companion object {
        @Provides
        @Singleton
        fun provideAuthConfig(): AuthConfig = AuthConfig.fromBuildConfig()

        /**
         * Null when Supabase is not configured — every consumer degrades gracefully so
         * the app stays fully usable offline without any secrets (§6).
         */
        @Provides
        @Singleton
        fun provideSupabaseClient(config: AuthConfig): SupabaseClient? {
            if (!config.isSupabaseConfigured) return null
            return createSupabaseClient(
                supabaseUrl = config.supabaseUrl,
                supabaseKey = config.supabaseAnonKey,
            ) {
                install(Auth)
                install(Postgrest)
                // Storage serves item photos (private `inventory-images` bucket, ADR-023).
                install(Storage)
            }
        }
    }
}
