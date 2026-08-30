package com.itsluminous.samaroh.core.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
import com.itsluminous.samaroh.core.database.SamarohDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Sign-out local-data wipe — ADDITIVE session contract (docs/decisions.md ADR-040).
 *
 * Sign-out removes ALL session-scoped local state so the next sign-in re-pulls cleanly
 * and another user's data never lingers on a shared device: the Room database (business
 * data, outbox, sync cursors, conflict log, Google link rows) is cleared, the
 * `onboarding_complete` flag is reset (next launch routes to onboarding), and every
 * module-contributed [SessionScopedStore] wipes its own DataStore state. Device-level
 * preferences (theme, language, reminder settings) are kept — they carry no user data.
 */

/**
 * A module-owned store holding session-scoped state that must be wiped on sign-out.
 * Contributed via Hilt `@IntoSet` (same pattern as `PostSyncHook`, ADR-024); valid when
 * no module contributes any.
 */
interface SessionScopedStore {
    suspend fun clearForSignOut()
}

/** Wipes all session-scoped local data after the auth session is dropped (ADR-040). */
interface SignOutCleaner {
    /**
     * Clears the Room database, resets the onboarding-complete flag and clears every
     * contributed [SessionScopedStore]. Call AFTER `SessionHolder.signOut()` so no sync
     * run can re-push cleared rows in between.
     */
    suspend fun clearAll()
}

@Singleton
class DefaultSignOutCleaner
    @Inject
    constructor(
        private val database: SamarohDatabase,
        @SettingsDataStore private val settings: DataStore<Preferences>,
        private val sessionScopedStores: Set<@JvmSuppressWildcards SessionScopedStore>,
    ) : SignOutCleaner {
        override suspend fun clearAll() {
            // Room's clearAllTables is blocking (and asserts off-main) — run on IO.
            withContext(Dispatchers.IO) { database.clearAllTables() }
            // Reset the first-launch flag so a restart lands on onboarding, not on empty
            // tabs in owner mode. Key owned by `:app` MainViewModel — keep in sync.
            settings.edit { it.remove(KEY_ONBOARDING_COMPLETE) }
            sessionScopedStores.forEach { it.clearForSignOut() }
        }

        private companion object {
            val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        }
    }
