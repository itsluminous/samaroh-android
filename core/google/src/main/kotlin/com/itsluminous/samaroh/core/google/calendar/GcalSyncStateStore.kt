package com.itsluminous.samaroh.core.google.calendar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

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
        @ApplicationContext context: Context,
    ) {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), SyncedEventState.serializer())

        private val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FILE_NAME) }

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

        private fun keyFor(businessId: String) = stringPreferencesKey("state_$businessId")

        private companion object {
            const val FILE_NAME = "gcal_sync_state"
        }
    }
