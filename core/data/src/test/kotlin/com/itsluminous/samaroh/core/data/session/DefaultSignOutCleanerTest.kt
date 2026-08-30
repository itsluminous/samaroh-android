package com.itsluminous.samaroh.core.data.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.entity.BusinessEntity
import com.itsluminous.samaroh.core.database.entity.OutboxEntity
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

/**
 * Sign-out local-data wipe (ADR-040): the Room database is emptied, the
 * `onboarding_complete` flag is reset (device prefs like theme are KEPT), and every
 * contributed [SessionScopedStore] is cleared.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultSignOutCleanerTest {
    private val database = inMemoryDatabase(ApplicationProvider.getApplicationContext())
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settingsFile = File.createTempFile("signout_test_settings", ".preferences_pb")
    private val settings =
        PreferenceDataStoreFactory.create(scope = storeScope) { settingsFile }

    private class RecordingSessionScopedStore : SessionScopedStore {
        var clearCalls = 0

        override suspend fun clearForSignOut() {
            clearCalls++
        }
    }

    private val contributedStore = RecordingSessionScopedStore()
    private val cleaner = DefaultSignOutCleaner(database, settings, setOf(contributedStore))

    private val onboardingKey = booleanPreferencesKey("onboarding_complete")
    private val themeKey = stringPreferencesKey("theme_mode")

    @After
    fun tearDown() {
        database.close()
        storeScope.cancel()
        settingsFile.delete()
    }

    @Test
    fun `clearAll empties the database, resets onboarding and clears contributed stores`() =
        runTest {
            database.businessDao().upsert(
                BusinessEntity(
                    id = Fixtures.BUSINESS_ID,
                    name = "fixture-business",
                    ownerName = "fixture-owner",
                    ownerUserId = Fixtures.USER_ID,
                    createdAt = Fixtures.NOW,
                    updatedAt = Fixtures.NOW,
                ),
            )
            database.outboxDao().enqueue(
                OutboxEntity(
                    entityType = "bookings",
                    entityId = "b-1",
                    operation = "upsert",
                    payloadJson = "{}",
                    createdAt = Instant.parse("2026-08-30T00:00:00Z"),
                ),
            )
            settings.edit {
                it[onboardingKey] = true
                it[themeKey] = "dark"
            }

            cleaner.clearAll()

            assertThat(database.businessDao().allBusinesses().first()).isEmpty()
            assertThat(database.outboxDao().pendingCount().first()).isEqualTo(0)
            val prefs = settings.data.first()
            assertThat(prefs[onboardingKey]).isNull()
            // Device-level preferences survive — they carry no user data (ADR-040).
            assertThat(prefs[themeKey]).isEqualTo("dark")
            assertThat(contributedStore.clearCalls).isEqualTo(1)
        }
}
