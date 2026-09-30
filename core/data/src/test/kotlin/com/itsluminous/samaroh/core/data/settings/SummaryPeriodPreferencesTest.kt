package com.itsluminous.samaroh.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Summary-period preference contract (ADR-091): the exact DataStore key, the this-month
 * default when unset or unrecognized, value round-trips, and the storage strings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryPeriodPreferencesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val storeScope = CoroutineScope(dispatcher + Job())

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tmp.root, "settings.preferences_pb")
        }
    }
    private val prefs by lazy { SummaryPeriodPreferences(dataStore) }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `unset defaults to this month`() =
        testScope.runTest {
            assertThat(prefs.expensesSummaryPeriod.first()).isEqualTo(SummaryPeriod.THIS_MONTH)
        }

    @Test
    fun `set values round-trip under the contract key with stable storage strings`() =
        testScope.runTest {
            prefs.setExpensesSummaryPeriod(SummaryPeriod.THIS_YEAR)
            assertThat(prefs.expensesSummaryPeriod.first()).isEqualTo(SummaryPeriod.THIS_YEAR)
            assertThat(dataStore.data.first()[SummaryPeriodPreferences.KEY_EXPENSES_SUMMARY]).isEqualTo("this_year")

            prefs.setExpensesSummaryPeriod(SummaryPeriod.ALL_TIME)
            assertThat(prefs.expensesSummaryPeriod.first()).isEqualTo(SummaryPeriod.ALL_TIME)
            assertThat(dataStore.data.first()[SummaryPeriodPreferences.KEY_EXPENSES_SUMMARY]).isEqualTo("all_time")

            prefs.setExpensesSummaryPeriod(SummaryPeriod.THIS_MONTH)
            assertThat(dataStore.data.first()[SummaryPeriodPreferences.KEY_EXPENSES_SUMMARY]).isEqualTo("this_month")
        }

    @Test
    fun `an unrecognized stored value falls back to this month`() =
        testScope.runTest {
            dataStore.edit { it[SummaryPeriodPreferences.KEY_EXPENSES_SUMMARY] = "fortnight" }

            assertThat(prefs.expensesSummaryPeriod.first()).isEqualTo(SummaryPeriod.THIS_MONTH)
        }

    @Test
    fun `storage strings are distinct and parse back`() {
        val values = SummaryPeriod.entries.map { it.storageValue }
        assertThat(values).containsNoDuplicates()
        SummaryPeriod.entries.forEach { assertThat(SummaryPeriod.fromStorage(it.storageValue)).isEqualTo(it) }
        assertThat(SummaryPeriod.fromStorage(null)).isEqualTo(SummaryPeriod.THIS_MONTH)
    }
}
