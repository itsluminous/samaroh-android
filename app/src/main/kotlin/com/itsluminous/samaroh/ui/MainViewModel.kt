package com.itsluminous.samaroh.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
import com.itsluminous.samaroh.core.data.sync.SyncStatus
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Theme selection read from the shared settings DataStore (§4.4; keys owned by feature:menu). */
data class ThemePrefs(
    /** `system` | `light` | `dark` — wire values of the menu tab's ThemeMode. */
    val themeMode: String = THEME_SYSTEM,
    val dynamicColor: Boolean = true,
) {
    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}

/** App-bar cloud icon state (§4.5): ✅ synced / 🔄 pending / ☁️⚠️ + count on errors. */
data class SyncIndicator(
    val pendingCount: Int = 0,
    val errorCount: Int = 0,
    /** True while a sync run is executing — the cloud icon animates (§4.5). */
    val syncing: Boolean = false,
)

/**
 * App-shell state (Wave-1 integration seam f): first-launch onboarding routing, theme
 * preferences, the app-bar sync indicator and the onboarding Google-link hand-off.
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        @SettingsDataStore private val settings: DataStore<Preferences>,
        syncStatus: SyncStatus,
        activeBusinessProvider: ActiveBusinessProvider,
        private val googleAccountLinker: GoogleAccountLinker,
    ) : ViewModel() {
        /** Null while the DataStore read is in flight — the shell waits before routing. */
        val onboardingComplete: StateFlow<Boolean?> =
            settings.data
                .map { prefs -> prefs[KEY_ONBOARDING_COMPLETE] ?: false }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /**
         * Active business name for the top app bar; null before onboarding creates one
         * (the shell falls back to the app name).
         */
        val activeBusinessName: StateFlow<String?> =
            activeBusinessProvider.activeBusiness
                .map { it?.name?.ifBlank { null } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        val themePrefs: StateFlow<ThemePrefs> =
            settings.data
                .map { prefs ->
                    ThemePrefs(
                        themeMode = prefs[KEY_THEME_MODE] ?: ThemePrefs.THEME_SYSTEM,
                        dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
                    )
                }.stateIn(viewModelScope, SharingStarted.Eagerly, ThemePrefs())

        val syncIndicator: StateFlow<SyncIndicator> =
            combine(syncStatus.pendingCount, syncStatus.itemErrors, syncStatus.isSyncing) { pending, errors, syncing ->
                SyncIndicator(pendingCount = pending, errorCount = errors.size, syncing = syncing)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncIndicator())

        /** Marks first-launch onboarding as done (§4.0 step 7) — persists across restarts. */
        fun completeOnboarding() {
            viewModelScope.launch { settings.edit { it[KEY_ONBOARDING_COMPLETE] = true } }
        }

        /**
         * Onboarding "Connect Google" hand-off to the W1-F link flow (§4.0 step 6).
         * Fire-and-forget: onboarding never blocks on it, and Settings shows the outcome.
         */
        fun connectGoogle(activityContext: Context) {
            viewModelScope.launch { googleAccountLinker.link(activityContext) }
        }

        private companion object {
            val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
            val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
            val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        }
    }
