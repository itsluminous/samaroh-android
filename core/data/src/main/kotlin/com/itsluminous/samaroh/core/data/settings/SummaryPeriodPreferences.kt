package com.itsluminous.samaroh.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The window a home-screen SUMMARY card totals over (ADR-091). Presentation-level: the
 * card's ViewModel resolves the window to a calendar range on the device's local date
 * and asks the repository for totals over it — no query contract changes beyond the
 * additive bounded-sum (see the ADR).
 */
enum class SummaryPeriod(
    val storageValue: String,
) {
    /** The current calendar month (default): first day through last day, inclusive. */
    THIS_MONTH("this_month"),

    /** The current calendar year: 1 Jan through 31 Dec, inclusive. */
    THIS_YEAR("this_year"),

    /** Every live entry regardless of its date. */
    ALL_TIME("all_time"),

    ;

    companion object {
        /** Stored string → period; unknown/unset falls back to [THIS_MONTH]. */
        fun fromStorage(value: String?): SummaryPeriod = entries.firstOrNull { it.storageValue == value } ?: THIS_MONTH
    }
}

/**
 * Per-device SUMMARY PERIOD preferences (ADR-091) in the shared settings DataStore, one
 * key per summary card so future cards can remember their windows independently.
 * Written by the card's own switcher; read by its ViewModel. Unset or unrecognized
 * stored values fall back to [SummaryPeriod.THIS_MONTH] — the product default.
 */
@Singleton
class SummaryPeriodPreferences
    @Inject
    constructor(
        @SettingsDataStore private val dataStore: DataStore<Preferences>,
    ) {
        /** Expenses tab → home totals card ("You gave" / "You got") window. */
        val expensesSummaryPeriod: Flow<SummaryPeriod> =
            dataStore.data.map { prefs -> SummaryPeriod.fromStorage(prefs[KEY_EXPENSES_SUMMARY]) }

        suspend fun setExpensesSummaryPeriod(period: SummaryPeriod) {
            dataStore.edit { it[KEY_EXPENSES_SUMMARY] = period.storageValue }
        }

        companion object {
            val KEY_EXPENSES_SUMMARY = stringPreferencesKey("summary_period_expenses")
        }
    }
