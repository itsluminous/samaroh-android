package com.itsluminous.samaroh.core.auth.di

import com.itsluminous.samaroh.core.auth.AuthConfig
import com.itsluminous.samaroh.core.auth.AuthRepository
import com.itsluminous.samaroh.core.auth.BuildConfig
import com.itsluminous.samaroh.core.auth.DefaultPermissionGuard
import com.itsluminous.samaroh.core.auth.MembershipRefresher
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.auth.RequestAuthDiagnostics
import com.itsluminous.samaroh.core.auth.SessionActiveBusinessProvider
import com.itsluminous.samaroh.core.auth.SessionCurrentUserProvider
import com.itsluminous.samaroh.core.auth.SessionHolder
import com.itsluminous.samaroh.core.auth.SupabaseAuthManager
import com.itsluminous.samaroh.core.auth.SupabaseMembershipRefresher
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.data.sync.SyncAuthGate
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    @Binds abstract fun bindSessionHolder(impl: SupabaseAuthManager): SessionHolder

    @Binds abstract fun bindAuthRepository(impl: SupabaseAuthManager): AuthRepository

    /** Sync pre-flight + signed-out signal (ADR-089): the engine never runs as `anon`. */
    @Binds abstract fun bindSyncAuthGate(impl: SupabaseAuthManager): SyncAuthGate

    @Binds abstract fun bindPermissionGuard(impl: DefaultPermissionGuard): PermissionGuard

    @Binds abstract fun bindMembershipRefresher(impl: SupabaseMembershipRefresher): MembershipRefresher

    /** Wave-1 session seam (docs/decisions.md ADR-017). */
    @Binds abstract fun bindActiveBusinessProvider(impl: SessionActiveBusinessProvider): ActiveBusinessProvider

    @Binds abstract fun bindCurrentUserProvider(impl: SessionCurrentUserProvider): CurrentUserProvider

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
        @OptIn(SupabaseInternal::class)
        fun provideSupabaseClient(config: AuthConfig): SupabaseClient? {
            if (!config.isSupabaseConfigured) return null
            val client =
                createSupabaseClient(
                    supabaseUrl = config.supabaseUrl,
                    supabaseKey = config.supabaseAnonKey,
                ) {
                    install(Auth)
                    install(Postgrest)
                }
            // Debug builds log the JWT role of every request (ADR-088) — `adb logcat -s
            // SamarohAuthz` tells anon-role (dropped session) pushes from user-role ones.
            if (BuildConfig.DEBUG) RequestAuthDiagnostics.install(client.httpClient.httpClient)
            return client
        }
    }
}
