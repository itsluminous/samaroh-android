package com.itsluminous.samaroh.feature.files

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Per-device Files listing layout (grid vs list), persisted in the shared settings DataStore (design §6). */
@Singleton
class FilesViewPreferences
    @Inject
    constructor(
        @SettingsDataStore private val dataStore: DataStore<Preferences>,
    ) {
        val gridView: Flow<Boolean> = dataStore.data.map { it[KEY_GRID] ?: false }

        suspend fun setGridView(grid: Boolean) {
            dataStore.edit { it[KEY_GRID] = grid }
        }

        private companion object {
            val KEY_GRID = booleanPreferencesKey("files_grid_view")
        }
    }
