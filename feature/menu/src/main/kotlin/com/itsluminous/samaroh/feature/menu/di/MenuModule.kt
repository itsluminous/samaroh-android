package com.itsluminous.samaroh.feature.menu.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.itsluminous.samaroh.core.data.sync.SyncStatusProvider
import com.itsluminous.samaroh.feature.menu.data.OutboxSyncStatusProvider
import com.itsluminous.samaroh.feature.menu.data.SettingsPreferencesDataSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** The device-settings DataStore (preferences file `"settings"`, §4.4). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

@Module
@InstallIn(SingletonComponent::class)
abstract class MenuModule {
    /**
     * Outbox-backed fallback until the W1-E sync engine ships the real provider —
     * INTEGRATOR: remove this binding when `core:sync` binds `SyncStatusProvider`
     * (docs/decisions.md ADR-007).
     */
    @Binds abstract fun bindSyncStatusProvider(impl: OutboxSyncStatusProvider): SyncStatusProvider

    companion object {
        @Provides
        @Singleton
        @SettingsDataStore
        fun provideSettingsDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile(SettingsPreferencesDataSource.FILE_NAME)
            }
    }
}
