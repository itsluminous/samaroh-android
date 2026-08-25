package com.itsluminous.samaroh.core.auth.di

import com.itsluminous.samaroh.core.auth.AuthConfig
import com.itsluminous.samaroh.core.auth.AuthRepository
import com.itsluminous.samaroh.core.auth.DefaultPermissionGuard
import com.itsluminous.samaroh.core.auth.MembershipRefresher
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.auth.SessionHolder
import com.itsluminous.samaroh.core.auth.SupabaseAuthManager
import com.itsluminous.samaroh.core.auth.SupabaseMembershipRefresher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    @Binds abstract fun bindSessionHolder(impl: SupabaseAuthManager): SessionHolder

    @Binds abstract fun bindAuthRepository(impl: SupabaseAuthManager): AuthRepository

    @Binds abstract fun bindPermissionGuard(impl: DefaultPermissionGuard): PermissionGuard

    @Binds abstract fun bindMembershipRefresher(impl: SupabaseMembershipRefresher): MembershipRefresher

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
            }
        }
    }
}
