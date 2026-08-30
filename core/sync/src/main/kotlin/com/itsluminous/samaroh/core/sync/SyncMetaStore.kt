package com.itsluminous.samaroh.core.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.itsluminous.samaroh.core.data.session.SessionScopedStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Persists sync-run metadata (§4.4 "last sync time"). Interface so tests can use an in-memory fake. */
interface SyncMetaStore {
    val lastSyncTime: Flow<Instant?>

    suspend fun recordSyncTime(at: Instant)

    /** Wipes all recorded metadata — the sign-out local-data wipe (ADR-040). */
    suspend fun clear()
}

private val Context.syncMetaDataStore by preferencesDataStore(name = "samaroh_sync_meta")

@Singleton
class DataStoreSyncMetaStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SyncMetaStore,
        SessionScopedStore {
        private val lastSyncKey = longPreferencesKey("last_sync_at")

        override val lastSyncTime: Flow<Instant?> =
            context.syncMetaDataStore.data.map { prefs -> prefs[lastSyncKey]?.let(Instant::ofEpochMilli) }

        override suspend fun recordSyncTime(at: Instant) {
            context.syncMetaDataStore.edit { prefs -> prefs[lastSyncKey] = at.toEpochMilli() }
        }

        override suspend fun clear() {
            context.syncMetaDataStore.edit { prefs -> prefs.clear() }
        }

        /** A stale "last sync" time must not leak to the next account (ADR-040). */
        override suspend fun clearForSignOut() = clear()
    }
