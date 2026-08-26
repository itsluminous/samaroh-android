package com.itsluminous.samaroh.e2e

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
import com.itsluminous.samaroh.core.data.settings.SettingsDataStoreModule
import com.itsluminous.samaroh.core.google.calendar.GcalSyncStateDataStore
import com.itsluminous.samaroh.core.google.calendar.GcalSyncStateModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Hilt creates a FRESH SingletonComponent per `@HiltAndroidTest` class-instance, but
 * the instrumentation process is shared by every test method — so a per-component
 * DataStore provider would open the same preferences file twice and crash
 * (`IllegalStateException: multiple DataStores active for the same file`). These
 * replacements pin each store to ONE process-wide instance; tests reset their contents
 * in `@Before` instead of recreating them.
 */
object ProcessWideStores {
    @Volatile private var settings: DataStore<Preferences>? = null

    @Volatile private var gcalSyncState: DataStore<Preferences>? = null

    fun settings(context: Context): DataStore<Preferences> =
        settings ?: synchronized(this) {
            settings ?: PreferenceDataStoreFactory
                .create { context.preferencesDataStoreFile(SettingsDataStoreModule.FILE_NAME) }
                .also { settings = it }
        }

    fun gcalSyncState(context: Context): DataStore<Preferences> =
        gcalSyncState ?: synchronized(this) {
            gcalSyncState ?: PreferenceDataStoreFactory
                .create { context.preferencesDataStoreFile(GcalSyncStateModule.FILE_NAME) }
                .also { gcalSyncState = it }
        }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SettingsDataStoreModule::class])
object TestSettingsDataStoreModule {
    @Provides
    @Singleton
    @SettingsDataStore
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = ProcessWideStores.settings(context)
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [GcalSyncStateModule::class])
object TestGcalSyncStateModule {
    @Provides
    @Singleton
    @GcalSyncStateDataStore
    fun provideGcalSyncStateDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = ProcessWideStores.gcalSyncState(context)
}
