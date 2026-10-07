package com.itsluminous.samaroh.feature.booking.reminders

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.feature.booking.FakeCurrentUserProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ADR-095 — the reminder ledger is per USER as well as per device: entries are namespaced
 * by the signed-in user's id (remembered across auth initialization), legacy unscoped
 * entries are adopted on the first pass, and sign-out wipes everything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReminderLedgerScopeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val storeScope = CoroutineScope(dispatcher + Job())
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Clock.fixed(Instant.parse("2026-10-07T06:00:00Z"), ZoneId.of("Asia/Kolkata"))

    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tmp.root, "settings.preferences_pb")
        }
    private val user = FakeCurrentUserProvider("user-a")
    private val ledger = ReminderLedger(dataStore, user)

    private val today = LocalDate.of(2026, 10, 7)
    private val key = ReminderLedgerKey.Upcoming("b-1", 1, today.plusDays(1))

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private suspend fun rawKeys(): Set<String> =
        dataStore.data
            .first()
            .asMap()
            .keys
            .map { it.name }
            .toSet()

    @Test
    fun `entries are namespaced by the signed-in user id`() =
        runTest(dispatcher) {
            ledger.mark(key, ReminderFireState.ACKED, keepUntil = today.plusDays(1))

            assertThat(rawKeys()).contains("reminder_ledger.user-a." + key.encode())
            assertThat(ledger.state(key)).isEqualTo(ReminderFireState.ACKED)

            // Another account on the same device does not inherit the acknowledgement.
            user.userId.value = "user-b"
            assertThat(ledger.entry(key)).isNull()
            assertThat(ledger.all()).isEmpty()

            // …and switching back finds it again.
            user.userId.value = "user-a"
            assertThat(ledger.state(key)).isEqualTo(ReminderFireState.ACKED)
        }

    @Test
    fun `the user scope is remembered while the auth session is still initializing`() =
        runTest(dispatcher) {
            ledger.mark(key, ReminderFireState.SNOOZED, keepUntil = today.plusDays(1), snoozedUntilMillis = 1L)

            // Boot/alarm receivers may run before the Supabase session has loaded: the
            // live provider emits null, yet the entries must still resolve.
            user.userId.value = null
            assertThat(ledger.scope()).isEqualTo("user-a")
            assertThat(ledger.all().keys).containsExactly(key)
        }

    @Test
    fun `signed-out owner mode uses the local scope`() =
        runTest(dispatcher) {
            val offline = ReminderLedger(dataStore, FakeCurrentUserProvider(null))

            offline.mark(key, ReminderFireState.FIRED, keepUntil = today.plusDays(1))

            assertThat(rawKeys()).contains("reminder_ledger.local." + key.encode())
        }

    @Test
    fun `prune adopts legacy unscoped entries into the current scope`() =
        runTest(dispatcher) {
            val legacyKey = stringPreferencesKey(ReminderLedger.PREFIX + key.encode())
            dataStore.edit { it[legacyKey] = ReminderLedgerEntry(ReminderFireState.ACKED, today.plusDays(1)).encode() }

            ledger.prune(today)

            assertThat(rawKeys()).doesNotContain(legacyKey.name)
            assertThat(ledger.state(key)).isEqualTo(ReminderFireState.ACKED)
        }

    @Test
    fun `prune drops expired entries of every scope`() =
        runTest(dispatcher) {
            ledger.mark(key, ReminderFireState.ACKED, keepUntil = today.minusDays(1))
            user.userId.value = "user-b"
            ledger.mark(key, ReminderFireState.ACKED, keepUntil = today.minusDays(1))

            ledger.prune(today)

            assertThat(rawKeys().filter { it.startsWith(ReminderLedger.PREFIX) }).isEmpty()
        }

    @Test
    fun `sign-out wipes every entry and the remembered scope`() =
        runTest(dispatcher) {
            ledger.mark(key, ReminderFireState.SNOOZED, keepUntil = today.plusDays(1), snoozedUntilMillis = clock.millis() + 60_000)
            user.userId.value = "user-b"
            ledger.mark(key, ReminderFireState.ACKED, keepUntil = today.plusDays(1))

            ReminderSessionStore(ledger, ReminderSnoozer(context, ledger, clock)).clearForSignOut()

            assertThat(rawKeys().filter { it.startsWith(ReminderLedger.PREFIX) }).isEmpty()
            assertThat(rawKeys()).doesNotContain(ReminderLedger.SCOPE_KEY.name)
            // A fresh sign-in (any account) starts from a clean slate.
            user.userId.value = null
            assertThat(ledger.scope()).isEqualTo(ReminderLedger.LOCAL_SCOPE)
        }
}
