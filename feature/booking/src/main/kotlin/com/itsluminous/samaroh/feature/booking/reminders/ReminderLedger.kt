package com.itsluminous.samaroh.feature.booking.reminders

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identity of ONE reminder delivery on THIS device (ADR-094). Two kinds:
 * - [Upcoming]: the "N days before" event reminder — keyed on the booking, the lead
 *   offset AND the start date, so editing the booking's dates (or changing the lead-day
 *   setting) yields a fresh key that fires again, while an unchanged booking never does;
 * - [Row]: a persisted payment / follow-up reminder row — keyed on its id (a chained
 *   successor row is a new key and fires anew).
 *
 * The wire form ([encode]/[decode]) rides in intents and DataStore keys.
 */
sealed interface ReminderLedgerKey {
    val bookingId: String

    data class Upcoming(
        override val bookingId: String,
        val daysAway: Int,
        val startDate: LocalDate,
    ) : ReminderLedgerKey

    data class Row(
        val reminderId: String,
        override val bookingId: String,
        val followUp: Boolean,
    ) : ReminderLedgerKey

    fun encode(): String =
        when (this) {
            is Upcoming -> listOf(UPCOMING, bookingId, daysAway.toString(), startDate.toString()).joinToString(SEP)
            is Row -> listOf(if (followUp) FOLLOW_UP else PAYMENT, reminderId, bookingId).joinToString(SEP)
        }

    companion object {
        private const val SEP = "|"
        private const val UPCOMING = "upcoming"
        private const val PAYMENT = "payment"
        private const val FOLLOW_UP = "followup"

        fun decode(wire: String?): ReminderLedgerKey? {
            val parts = wire?.split(SEP) ?: return null
            return runCatching {
                when (parts.getOrNull(0)) {
                    UPCOMING -> Upcoming(parts[1], parts[2].toInt(), LocalDate.parse(parts[3]))
                    PAYMENT -> Row(parts[1], parts[2], followUp = false)
                    FOLLOW_UP -> Row(parts[1], parts[2], followUp = true)
                    else -> null
                }
            }.getOrNull()
        }
    }
}

/**
 * Lifecycle of a reminder delivery on this device. A reminder is posted AT MOST ONCE
 * per key: [SCHEDULED] (exact alarm armed, not yet rung), [FIRED] (notification /
 * popup shown), [ACKED] (user dismissed, tapped, viewed or acted — never shown again
 * for this key) and [SNOOZED] (acked now, one-shot re-fire at [ReminderLedgerEntry.snoozedUntilMillis]).
 */
enum class ReminderFireState(
    val wire: String,
) {
    SCHEDULED("scheduled"),
    FIRED("fired"),
    ACKED("acked"),
    SNOOZED("snoozed"),
    ;

    companion object {
        fun fromWire(value: String): ReminderFireState? = entries.firstOrNull { it.wire == value }
    }
}

data class ReminderLedgerEntry(
    val state: ReminderFireState,
    /** Last local date this entry is still relevant; pruned the day after. */
    val keepUntil: LocalDate,
    /** Only for [ReminderFireState.SNOOZED]: when the one-shot re-fire is due. */
    val snoozedUntilMillis: Long? = null,
) {
    /** Whether the planner must leave this reminder alone (already delivered / handled). */
    val blocksPlanning: Boolean get() = state != ReminderFireState.SCHEDULED

    fun encode(): String = listOfNotNull(state.wire, keepUntil.toString(), snoozedUntilMillis?.toString()).joinToString("|")

    companion object {
        fun decode(wire: String): ReminderLedgerEntry? {
            val parts = wire.split("|")
            val state = parts.getOrNull(0)?.let(ReminderFireState::fromWire) ?: return null
            return runCatching {
                ReminderLedgerEntry(
                    state = state,
                    keepUntil = LocalDate.parse(parts[1]),
                    snoozedUntilMillis = parts.getOrNull(2)?.toLong(),
                )
            }.getOrNull()
        }
    }
}

/**
 * Device-local record of which reminders have been delivered and acknowledged
 * (ADR-094, the "dismissed reminder keeps coming back" bug). Lives in the shared
 * settings DataStore (one key per entry under the `reminder_ledger.` prefix) so it
 * survives process death and reboot without a Room/schema change — notifications are
 * device state, not synced business data, so the ledger is deliberately NOT a synced
 * row. Entries expire via [prune] (upcoming: the event's start date; rows: a generous
 * horizon) so the store never grows unbounded.
 */
@Singleton
class ReminderLedger
    @Inject
    constructor(
        @SettingsDataStore private val dataStore: DataStore<Preferences>,
    ) {
        private fun prefKey(key: ReminderLedgerKey) = stringPreferencesKey(PREFIX + key.encode())

        suspend fun entry(key: ReminderLedgerKey): ReminderLedgerEntry? =
            dataStore.data.first()[prefKey(key)]?.let(ReminderLedgerEntry::decode)

        suspend fun state(key: ReminderLedgerKey): ReminderFireState? = entry(key)?.state

        suspend fun mark(
            key: ReminderLedgerKey,
            state: ReminderFireState,
            keepUntil: LocalDate,
            snoozedUntilMillis: Long? = null,
        ) {
            dataStore.edit { it[prefKey(key)] = ReminderLedgerEntry(state, keepUntil, snoozedUntilMillis).encode() }
        }

        /** Marks [key] acknowledged, keeping its existing horizon (or [fallbackKeepUntil] when unknown). */
        suspend fun ack(
            key: ReminderLedgerKey,
            fallbackKeepUntil: LocalDate,
        ) {
            dataStore.edit { prefs ->
                val keep = prefs[prefKey(key)]?.let(ReminderLedgerEntry::decode)?.keepUntil ?: fallbackKeepUntil
                prefs[prefKey(key)] = ReminderLedgerEntry(ReminderFireState.ACKED, keep).encode()
            }
        }

        suspend fun remove(key: ReminderLedgerKey) {
            dataStore.edit { it.remove(prefKey(key)) }
        }

        /** Every live entry, keyed — the boot pass uses it to re-arm snoozes/alarms. */
        suspend fun all(): Map<ReminderLedgerKey, ReminderLedgerEntry> =
            dataStore.data
                .first()
                .asMap()
                .mapNotNull { (k, v) ->
                    if (!k.name.startsWith(PREFIX) || v !is String) return@mapNotNull null
                    val key = ReminderLedgerKey.decode(k.name.removePrefix(PREFIX)) ?: return@mapNotNull null
                    val entry = ReminderLedgerEntry.decode(v) ?: return@mapNotNull null
                    key to entry
                }.toMap()

        /** Drops entries whose relevance window ended before [today]. */
        suspend fun prune(today: LocalDate) {
            dataStore.edit { prefs ->
                prefs
                    .asMap()
                    .keys
                    .filter { it.name.startsWith(PREFIX) }
                    .forEach { k ->
                        val entry = (prefs[k] as? String)?.let(ReminderLedgerEntry::decode)
                        if (entry == null || entry.keepUntil.isBefore(today)) prefs.remove(k)
                    }
            }
        }

        /**
         * Reboot: the OS dropped every posted notification and armed alarm. FIRED
         * entries the user never acknowledged go back to unplanned so the next pass
         * re-posts them (lost by the system, not dismissed by the user); SCHEDULED and
         * SNOOZED entries stay and are re-armed by the boot pass; ACKED stays acked.
         */
        suspend fun resetFiredForBoot() {
            dataStore.edit { prefs ->
                prefs
                    .asMap()
                    .keys
                    .filter { it.name.startsWith(PREFIX) }
                    .forEach { k ->
                        val entry = (prefs[k] as? String)?.let(ReminderLedgerEntry::decode)
                        if (entry?.state == ReminderFireState.FIRED) prefs.remove(k)
                    }
            }
        }

        companion object {
            const val PREFIX = "reminder_ledger."

            /** Horizon for payment/follow-up row entries (rows chain weekly; 90 days is ample). */
            const val ROW_KEEP_DAYS = 90L
        }
    }
