package com.itsluminous.samaroh.e2e

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
import com.itsluminous.samaroh.core.auth.di.AuthModule
import com.itsluminous.samaroh.core.data.image.ItemImageResolver
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.github.jan.supabase.SupabaseClient
import javax.inject.Singleton

/**
 * Mirrors the production [AuthModule] EXCEPT the configuration, which is pinned to
 * "Supabase unconfigured" (null client): the developer machine's `local.properties`
 * may carry real credentials AND the emulator may hold a persisted signed-in session —
 * without this pin the session's user id makes [SessionCurrentUserProvider] resolve a
 * NON-owner against the fixture business (view-only, no FAB) and the suite flakes by
 * machine. Pinned, every test runs the documented offline-first owner-mode default,
 * identical to CI (which has no secrets).
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AuthModule::class])
abstract class TestAuthModule {
    @Binds abstract fun bindSessionHolder(impl: SupabaseAuthManager): SessionHolder

    @Binds abstract fun bindAuthRepository(impl: SupabaseAuthManager): AuthRepository

    @Binds abstract fun bindPermissionGuard(impl: DefaultPermissionGuard): PermissionGuard

    @Binds abstract fun bindMembershipRefresher(impl: SupabaseMembershipRefresher): MembershipRefresher

    @Binds abstract fun bindActiveBusinessProvider(impl: SessionActiveBusinessProvider): ActiveBusinessProvider

    @Binds abstract fun bindCurrentUserProvider(impl: SessionCurrentUserProvider): CurrentUserProvider

    @Binds abstract fun bindItemImageResolver(impl: StorageItemImageResolver): ItemImageResolver

    companion object {
        /** Blank values = unconfigured; every consumer degrades to offline-only. */
        @Provides
        @Singleton
        fun provideAuthConfig(): AuthConfig = AuthConfig(supabaseUrl = "", supabaseAnonKey = "", googleWebClientId = "")

        @Provides
        @Singleton
        fun provideSupabaseClient(): SupabaseClient? = null
    }
}
