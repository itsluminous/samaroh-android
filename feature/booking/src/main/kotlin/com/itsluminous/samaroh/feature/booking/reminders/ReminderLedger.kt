package com.itsluminous.samaroh.feature.booking.reminders

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
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
 *
 * Entries are additionally scoped to the SIGNED-IN USER (ADR-095): every key is
 * `reminder_ledger.<userScope>.<encoded key>`, where the scope is the Supabase user id
 * (or `local` for signed-out / offline-continue owner mode). The scope is remembered in
 * the same DataStore so receivers that run before the auth session has loaded (boot,
 * alarms — `CurrentUserProvider` emits null while initializing) still address the right
 * entries. Sign-out wipes the whole ledger ([clearAll], via `ReminderSessionStore`), so
 * one account's acknowledgements never leak to the next account on a shared device; the
 * scope is belt-and-braces for the same guarantee. Legacy unscoped entries written by
 * builds before ADR-095 are adopted into the current scope by [prune].
 */
@Singleton
class ReminderLedger
    @Inject
    constructor(
        @SettingsDataStore private val dataStore: DataStore<Preferences>,
        private val currentUserProvider: CurrentUserProvider,
    ) {
        /**
         * The ledger namespace for the current session: the live user id when known,
         * else the last remembered one, else [LOCAL_SCOPE]. A newly observed user id is
         * remembered so later lookups during auth initialization resolve the same scope.
         */
        suspend fun scope(): String {
            val live = currentUserProvider.currentUserId.first()
            val remembered = dataStore.data.first()[SCOPE_KEY]
            if (live != null) {
                if (remembered != live) dataStore.edit { it[SCOPE_KEY] = live }
                return live
            }
            return remembered ?: LOCAL_SCOPE
        }

        private fun prefKey(
            scope: String,
            key: ReminderLedgerKey,
        ) = stringPreferencesKey(PREFIX + scope + SCOPE_SEP + key.encode())

        private suspend fun prefKey(key: ReminderLedgerKey) = prefKey(scope(), key)

        suspend fun entry(key: ReminderLedgerKey): ReminderLedgerEntry? =
            dataStore.data.first()[prefKey(key)]?.let(ReminderLedgerEntry::decode)

        suspend fun state(key: ReminderLedgerKey): ReminderFireState? = entry(key)?.state

        suspend fun mark(
            key: ReminderLedgerKey,
            state: ReminderFireState,
            keepUntil: LocalDate,
            snoozedUntilMillis: Long? = null,
        ) {
            val prefKey = prefKey(key)
            dataStore.edit { it[prefKey] = ReminderLedgerEntry(state, keepUntil, snoozedUntilMillis).encode() }
        }

        /** Marks [key] acknowledged, keeping its existing horizon (or [fallbackKeepUntil] when unknown). */
        suspend fun ack(
            key: ReminderLedgerKey,
            fallbackKeepUntil: LocalDate,
        ) {
            val prefKey = prefKey(key)
            dataStore.edit { prefs ->
                val keep = prefs[prefKey]?.let(ReminderLedgerEntry::decode)?.keepUntil ?: fallbackKeepUntil
                prefs[prefKey] = ReminderLedgerEntry(ReminderFireState.ACKED, keep).encode()
            }
        }

        suspend fun remove(key: ReminderLedgerKey) {
            val prefKey = prefKey(key)
            dataStore.edit { it.remove(prefKey) }
        }

        /** Every live entry of the CURRENT scope, keyed — the boot pass uses it to re-arm snoozes/alarms. */
        suspend fun all(): Map<ReminderLedgerKey, ReminderLedgerEntry> {
            val scope = scope()
            return dataStore.data
                .first()
                .asMap()
                .mapNotNull { (k, v) ->
                    val parsed = parse(k.name) ?: return@mapNotNull null
                    if (parsed.scope != scope || v !is String) return@mapNotNull null
                    val entry = ReminderLedgerEntry.decode(v) ?: return@mapNotNull null
                    parsed.key to entry
                }.toMap()
        }

        /**
         * Drops entries (of every scope) whose relevance window ended before [today], and
         * adopts legacy unscoped entries (pre-ADR-095 builds) into the current scope so an
         * upgrade keeps "dismissed stays dismissed".
         */
        suspend fun prune(today: LocalDate) {
            val scope = scope()
            dataStore.edit { prefs ->
                prefs
                    .asMap()
                    .keys
                    .filter { it.name.startsWith(PREFIX) }
                    .forEach { k ->
                        val entry = (prefs[k] as? String)?.let(ReminderLedgerEntry::decode)
                        val parsed = parse(k.name)
                        when {
                            entry == null || parsed == null || entry.keepUntil.isBefore(today) -> prefs.remove(k)
                            parsed.legacy -> {
                                prefs.remove(k)
                                val scoped = prefKey(scope, parsed.key)
                                if (prefs[scoped] == null) prefs[scoped] = entry.encode()
                            }
                        }
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

        /** Sign-out (ADR-040/095): forget every entry of every scope and the remembered scope. */
        suspend fun clearAll() {
            dataStore.edit { prefs ->
                prefs
                    .asMap()
                    .keys
                    .filter { it.name.startsWith(PREFIX) }
                    .forEach { prefs.remove(it) }
                prefs.remove(SCOPE_KEY)
            }
        }

        private class ParsedKey(
            val scope: String,
            val key: ReminderLedgerKey,
            val legacy: Boolean,
        )

        /** `reminder_ledger.<scope>.<encoded>`; a pre-ADR-095 key has no scope segment. */
        private fun parse(name: String): ParsedKey? {
            if (!name.startsWith(PREFIX)) return null
            val rest = name.removePrefix(PREFIX)
            val dot = rest.indexOf(SCOPE_SEP)
            if (dot < 0) {
                val key = ReminderLedgerKey.decode(rest) ?: return null
                return ParsedKey(LOCAL_SCOPE, key, legacy = true)
            }
            val key = ReminderLedgerKey.decode(rest.substring(dot + 1)) ?: return null
            return ParsedKey(rest.substring(0, dot), key, legacy = false)
        }

        companion object {
            const val PREFIX = "reminder_ledger."

            /** Scope used while no user is (or was) signed in: offline-continue owner mode. */
            const val LOCAL_SCOPE = "local"

            private const val SCOPE_SEP = '.'

            /** The remembered user scope — survives auth initialization, cleared on sign-out. */
            val SCOPE_KEY = stringPreferencesKey("reminder_ledger_scope")

            /** Horizon for payment/follow-up row entries (rows chain weekly; 90 days is ample). */
            const val ROW_KEEP_DAYS = 90L
        }
    }
