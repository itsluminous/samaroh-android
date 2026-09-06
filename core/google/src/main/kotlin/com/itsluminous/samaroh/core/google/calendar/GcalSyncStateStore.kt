package com.itsluminous.samaroh.core.google.calendar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.itsluminous.samaroh.core.data.session.SessionScopedStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for the `gcal_sync_state` preferences DataStore (single instance per file). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GcalSyncStateDataStore

/**
 * Provides the `gcal_sync_state` DataStore as the ONE Hilt singleton for its file —
 * DataStore crashes when two instances open the same file (ADR-016 rule), so the
 * instance is a module-provided binding (also replaceable in instrumented tests).
 */
@Module
@InstallIn(SingletonComponent::class)
object GcalSyncStateModule {
    const val FILE_NAME = "gcal_sync_state"

    @Provides
    @Singleton
    @GcalSyncStateDataStore
    fun provideGcalSyncStateDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FILE_NAME) }
}

/**
 * Device-local record of what was last pushed to the calendar, keyed per business
 * (bookingId → eventId + content fingerprint). Local-only by design: the synced
 * `bookings.gcal_event_id` column stores the event id for other devices, while the
 * fingerprint diffing is a per-device optimization that never needs to sync.
 */
@Singleton
class GcalSyncStateStore
    @Inject
    constructor(
        @GcalSyncStateDataStore private val dataStore: DataStore<Preferences>,
    ) : SessionScopedStore {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), SyncedEventState.serializer())

        suspend fun read(businessId: String): Map<String, SyncedEventState> {
            val raw = dataStore.data.first()[keyFor(businessId)] ?: return emptyMap()
            return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
        }

        suspend fun write(
            businessId: String,
            state: Map<String, SyncedEventState>,
        ) {
            dataStore.edit { prefs ->
                if (state.isEmpty()) {
                    prefs.remove(keyFor(businessId))
                } else {
                    prefs[keyFor(businessId)] = json.encodeToString(serializer, state)
                }
            }
        }

        suspend fun clear(businessId: String) = write(businessId, emptyMap())

        /**
         * The calendar display name this device last set/verified for [businessId]
         * (ADR-048). Device-local rename detection: a business-name edit makes the
         * cached value stale, which is what lets a no-op pass skip ALL calendar HTTP
         * (the free-echo invariant) while a rename still happens promptly. Another
         * device re-verifying once (its own cache is empty) is an idempotent PATCH.
         */
        suspend fun readCalendarName(businessId: String): String? = dataStore.data.first()[nameKeyFor(businessId)]

        suspend fun writeCalendarName(
            businessId: String,
            name: String?,
        ) {
            dataStore.edit { prefs ->
                if (name == null) {
                    prefs.remove(nameKeyFor(businessId))
                } else {
                    prefs[nameKeyFor(businessId)] = name
                }
            }
        }

        /**
         * Sign-out wipe (ADR-040): drops the push state of EVERY business so stale
         * fingerprints can't suppress calendar pushes for the next account on this device.
         */
        override suspend fun clearForSignOut() {
            dataStore.edit { prefs -> prefs.clear() }
        }

        private fun keyFor(businessId: String) = stringPreferencesKey("state_$businessId")

        private fun nameKeyFor(businessId: String) = stringPreferencesKey("calendar_name_$businessId")
    }
