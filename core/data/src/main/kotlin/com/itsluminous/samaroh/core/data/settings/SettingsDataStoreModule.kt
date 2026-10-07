package com.itsluminous.samaroh.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The device-settings preferences DataStore (file `"settings"`, §4.4).
 *
 * SINGLE Hilt-provided instance shared by every consumer (`feature:menu` settings
 * screens, `feature:booking` reminder prefs, the `:app` theme/onboarding wiring) —
 * DataStore crashes at runtime if two instances open the same file, so no module may
 * create its own delegate for this file (integration seam, docs/decisions.md ADR-016).
 *
 * Key contract (owners in parentheses):
 * - `theme_mode`, `dynamic_color` (menu Settings; read by `:app` theme)
 * - `booking_reminder_lead_days`, `booking_reminder_style`, `booking_reminder_sound_uri`
 *   (menu Settings; read by booking's reminder engine)
 * - `onboarding_complete` (`:app` first-launch routing)
 * - `image_quality_bills`, `image_quality_item_photos` ([ImageQualityPreferences];
 *   written by menu Settings, read by the expense-attachment and item-photo encoders)
 * - `sort_order_inventory_stock`, `sort_order_expenses_parties` ([ListSortPreferences];
 *   written by the stock/party lists' sort menus, read by their ViewModels — ADR-069)
 * - `reminder_ledger.<userScope>.<key>` (booking's `ReminderLedger`, ADR-094/095):
 *   per-device, per-user delivered/acknowledged/snoozed state of each reminder;
 *   self-pruning, never synced, wiped on sign-out) and `reminder_ledger_scope` (the
 *   remembered user scope — cleared on sign-out)
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

@Module
@InstallIn(SingletonComponent::class)
object SettingsDataStoreModule {
    /** DataStore preferences file name — the cross-feature contract. */
    const val FILE_NAME = "settings"

    @Provides
    @Singleton
    @SettingsDataStore
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile(FILE_NAME)
        }
}
