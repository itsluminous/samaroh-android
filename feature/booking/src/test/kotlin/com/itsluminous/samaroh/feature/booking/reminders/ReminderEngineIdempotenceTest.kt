package com.itsluminous.samaroh.feature.booking.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.ReplicaIntegrity
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.booking.FakeBookingRepository
import com.itsluminous.samaroh.feature.booking.FakeBusinessRepository
import com.itsluminous.samaroh.feature.booking.FakeEventTypeRepository
import com.itsluminous.samaroh.feature.booking.domain.BuiltInEventType
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.seededPresetFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * ADR-094 — the "dismissed reminder keeps coming back" bug. The engine pass runs after
 * EVERY sync pull (ADR-060 §4) on top of the daily 09:00 run, so delivery must be
 * idempotent per device: a reminder key posts at most once, a dismissed one never again,
 * and a snoozed one exactly once more at the chosen time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReminderEngineIdempotenceTest {
    @get:Rule val tmp = TemporaryFolder()

    // 14:00 local — the typical "post-sync pass after the 09:00 run" moment.
    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(Fixtures.NOW, zone)
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val dispatcher = UnconfinedTestDispatcher()
    private val storeScope = CoroutineScope(dispatcher + Job())
    private val emptyCatalog =
        object : EventTypeCatalog {
            override val eventTypes: List<BuiltInEventType> = emptyList()
        }

    private val bookingRepository = FakeBookingRepository()
    private val businessRepository = FakeBusinessRepository(listOf(Fixtures.business()))

    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tmp.root, "settings.preferences_pb")
        }
    private val ledger = ReminderLedger(dataStore)
    private val snoozer = ReminderSnoozer(context, ledger, clock)

    private fun engine(): ReminderEngine =
        ReminderEngine(
            context = context,
            bookingRepository = bookingRepository,
            businessRepository = businessRepository,
            eventTypeRepository = FakeEventTypeRepository(seededPresetFixtures()),
            eventTypes = emptyCatalog,
            notifier = BookingNotifier(context, FullScreenTakeover(context)),
            prefs = BookingReminderPrefs(dataStore),
            replicaIntegrity = ReplicaIntegrity { true },
            ledger = ledger,
            snoozer = snoozer,
            clock = clock,
        )

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private suspend fun selectStyle(style: ReminderStyle) {
        dataStore.edit { it[stringPreferencesKey("booking_reminder_style")] = style.wire }
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private val notificationManager get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    /** The OS/user removing every posted notification (no deleteIntent is delivered here). */
    private fun clearShade() = context.getSystemService(NotificationManager::class.java).cancelAll()

    private val alarmManager get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    private suspend fun seedTomorrowBooking() =
        Fixtures.booking(startDate = today().plusDays(1), endDate = today().plusDays(1)).also { bookingRepository.saveBooking(it) }

    private fun upcomingKey(bookingId: String) = ReminderLedgerKey.Upcoming(bookingId, 1, today().plusDays(1))

    // ---- the bug: notification style ----

    @Test
    fun `upcoming notification is posted once and NOT re-posted by repeated passes`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.NOTIFICATION)
            val booking = seedTomorrowBooking()

            engine().runDailyPass()
            assertThat(notificationManager.allNotifications).hasSize(1)
            assertThat(ledger.state(upcomingKey(booking.id))).isEqualTo(ReminderFireState.FIRED)

            // The OS cleared it (reboot-less case: e.g. channel wipe) WITHOUT a deleteIntent —
            // a FIRED key still never re-posts: "past-due fires post at most once".
            clearShade()
            engine().runDailyPass()
            engine().runDailyPass()
            assertThat(notificationManager.allNotifications).isEmpty()
        }

    @Test
    fun `swipe-dismiss (deleteIntent) acknowledges - later passes never bring it back`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.NOTIFICATION)
            val booking = seedTomorrowBooking()
            engine().runDailyPass()

            val posted = notificationManager.allNotifications.single()
            val deleteIntent = shadowOf(checkNotNull(posted.deleteIntent)).savedIntent
            assertThat(deleteIntent.component?.className).isEqualTo(ReminderAckReceiver::class.java.name)
            val key = checkNotNull(ReminderLedgerKey.decode(deleteIntent.getStringExtra(EXTRA_LEDGER_KEY)))
            assertThat(key).isEqualTo(upcomingKey(booking.id))

            // What ReminderAckReceiver does with that intent:
            clearShade()
            ledger.ack(key, fallbackKeepUntil = today())

            engine().runDailyPass()
            assertThat(notificationManager.allNotifications).isEmpty()
            assertThat(ledger.state(key)).isEqualTo(ReminderFireState.ACKED)
        }

    @Test
    fun `editing the booking's date yields a new key that fires again`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.NOTIFICATION)
            val booking = seedTomorrowBooking()
            engine().runDailyPass()
            ledger.ack(upcomingKey(booking.id), fallbackKeepUntil = today())
            clearShade()

            // Lead days 1 and 2 configured; the booking moves two days out → a different key.
            dataStore.edit {
                it[
                    androidx.datastore.preferences.core.stringSetPreferencesKey(
                        "booking_reminder_lead_days",
                    ),
                ] = setOf("1", "2")
            }
            bookingRepository.saveBooking(booking.copy(startDate = today().plusDays(2), endDate = today().plusDays(2)))
            engine().runDailyPass()

            assertThat(notificationManager.allNotifications).hasSize(1)
            assertThat(ledger.state(ReminderLedgerKey.Upcoming(booking.id, 2, today().plusDays(2)))).isEqualTo(ReminderFireState.FIRED)
        }

    // ---- the bug: full-screen styles (alarm re-armed at now+1s on every pass) ----

    @Test
    fun `full-screen alarm is armed once, fires once, and is not re-armed after firing`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.FULLSCREEN)
            val booking = seedTomorrowBooking()
            val key = upcomingKey(booking.id)

            engine().runDailyPass()
            engine().runDailyPass() // post-sync pass minutes later
            assertThat(alarmManager.scheduledAlarms).hasSize(1) // same PendingIntent → replaced, not stacked
            assertThat(ledger.state(key)).isEqualTo(ReminderFireState.SCHEDULED)
            val armed = alarmManager.scheduledAlarms.single()
            assertThat(shadowOf(armed.operation).savedIntent.getStringExtra(EXTRA_LEDGER_KEY)).isEqualTo(key.encode())

            // The alarm rings: the receiver posts and records FIRED (mirrored here without Hilt).
            context.getSystemService(AlarmManager::class.java).cancel(checkNotNull(armed.operation))
            BookingNotifier(context, FullScreenTakeover(context))
                .postFullScreenUpcomingReminder(booking.id, "t", 1, null, ReminderStyle.FULLSCREEN, key)
            ledger.mark(key, ReminderFireState.FIRED, keepUntil = booking.startDate)
            assertThat(notificationManager.allNotifications).hasSize(1)

            // User dismisses the popup/notification; subsequent sync passes must NOT re-arm.
            clearShade()
            ledger.ack(key, fallbackKeepUntil = booking.startDate)
            engine().runDailyPass()
            engine().runDailyPass()
            assertThat(alarmManager.scheduledAlarms).isEmpty()
            assertThat(notificationManager.allNotifications).isEmpty()
        }

    // ---- payment rows ----

    @Test
    fun `payment reminder notification posts once per row and dismissal sticks`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.NOTIFICATION)
            val booking =
                Fixtures.booking(startDate = today().minusDays(3), endDate = today().minusDays(2), totalAmountPaise = 2_00_000_00L)
            bookingRepository.saveBooking(booking)
            bookingRepository.recordPayment(Fixtures.payment(bookingId = booking.id, amountPaise = 50_000_00L))

            engine().runDailyPass()
            val posted = notificationManager.allNotifications.single()
            val reminder = bookingRepository.remindersForBooking(booking.id).single()
            val key = ReminderLedgerKey.Row(reminder.id, booking.id, followUp = false)
            assertThat(ReminderLedgerKey.decode(shadowOf(checkNotNull(posted.deleteIntent)).savedIntent.getStringExtra(EXTRA_LEDGER_KEY)))
                .isEqualTo(key)

            clearShade()
            ledger.ack(key, fallbackKeepUntil = today())
            engine().runDailyPass()
            assertThat(notificationManager.allNotifications).isEmpty()
            // ADR-064 untouched: the row itself stays PENDING (it lives on the in-app card).
            assertThat(bookingRepository.reminder(reminder.id)?.status).isEqualTo(ReminderStatus.PENDING)
        }

    // ---- snooze ----

    @Test
    fun `snooze arms a one-shot alarm, blocks planning, and refire posts exactly once`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.NOTIFICATION)
            val booking = seedTomorrowBooking()
            val key = upcomingKey(booking.id)
            engine().runDailyPass()
            clearShade()

            val fireAt = snoozer.snooze(key, SnoozePreset.TEN_MINUTES, keepUntil = booking.startDate)
            assertThat(fireAt).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)))
            val armed = alarmManager.scheduledAlarms.single()
            assertThat(armed.triggerAtTime).isEqualTo(fireAt.toEpochMilli())
            assertThat(shadowOf(armed.operation).savedIntent.component?.className).isEqualTo(ReminderSnoozeAlarmReceiver::class.java.name)
            val entry = checkNotNull(ledger.entry(key))
            assertThat(entry.state).isEqualTo(ReminderFireState.SNOOZED)
            assertThat(entry.snoozedUntilMillis).isEqualTo(fireAt.toEpochMilli())

            // Sync passes while snoozed leave it alone.
            engine().runDailyPass()
            assertThat(notificationManager.allNotifications).isEmpty()

            // The snooze alarm rings → one re-post, back to FIRED; passes still leave it alone.
            engine().refire(key)
            assertThat(notificationManager.allNotifications).hasSize(1)
            assertThat(ledger.state(key)).isEqualTo(ReminderFireState.FIRED)
            clearShade()
            engine().runDailyPass()
            assertThat(notificationManager.allNotifications).isEmpty()
        }

    @Test
    fun `snooze refire respects the current style`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.FULLSCREEN)
            val booking = seedTomorrowBooking()
            val key = upcomingKey(booking.id)
            snoozer.snooze(key, SnoozePreset.ONE_HOUR, keepUntil = booking.startDate)

            engine().refire(key)

            val posted = notificationManager.allNotifications.single()
            assertThat(posted.fullScreenIntent).isNotNull()
            assertThat(
                posted.actions.map {
                    it.title.toString()
                },
            ).contains(context.getString(com.itsluminous.samaroh.core.i18n.R.string.booking_reminder_snooze))
        }

    @Test
    fun `snoozed upcoming reminder is dropped when the booking is cancelled or deleted meanwhile`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.NOTIFICATION)
            val booking = seedTomorrowBooking()
            val key = upcomingKey(booking.id)
            snoozer.snooze(key, SnoozePreset.THIRTY_MINUTES, keepUntil = booking.startDate)

            bookingRepository.saveBooking(booking.copy(status = BookingStatus.CANCELLED))
            engine().refire(key)
            assertThat(notificationManager.allNotifications).isEmpty()
            assertThat(ledger.entry(key)).isNull()

            snoozer.snooze(key, SnoozePreset.THIRTY_MINUTES, keepUntil = booking.startDate)
            bookingRepository.deleteBooking(booking.id)
            engine().refire(key)
            assertThat(notificationManager.allNotifications).isEmpty()
            assertThat(ledger.entry(key)).isNull()
        }

    @Test
    fun `snoozed payment reminder is cancelled when the booking becomes paid`() =
        runTest(dispatcher) {
            selectStyle(ReminderStyle.NOTIFICATION)
            val booking =
                Fixtures.booking(startDate = today().minusDays(3), endDate = today().minusDays(2), totalAmountPaise = 1_00_000_00L)
            bookingRepository.saveBooking(booking)
            val reminder =
                PaymentReminder(
                    id = UUID.randomUUID().toString(),
                    bookingId = booking.id,
                    businessId = Fixtures.BUSINESS_ID,
                    remindOn = today(),
                    status = ReminderStatus.PENDING,
                    amountDueSnapshotPaise = 1_00_000_00L,
                    createdAt = Fixtures.NOW,
                    updatedAt = Fixtures.NOW,
                )
            bookingRepository.saveReminder(reminder)
            val key = ReminderLedgerKey.Row(reminder.id, booking.id, followUp = false)
            snoozer.snooze(key, SnoozePreset.THREE_HOURS, keepUntil = today().plusDays(90))
            assertThat(alarmManager.scheduledAlarms).hasSize(1)

            // Paid in full on another device, then synced: the planning pass dismisses the
            // row (ADR-064 case 1) AND disarms the snooze so it never rings.
            bookingRepository.recordPayment(Fixtures.payment(bookingId = booking.id, amountPaise = 1_00_000_00L))
            engine().runDailyPass()

            assertThat(bookingRepository.reminder(reminder.id)?.status).isEqualTo(ReminderStatus.DISMISSED)
            assertThat(alarmManager.scheduledAlarms).isEmpty()
            assertThat(ledger.entry(key)).isNull()
            // Belt and braces: even a stray fire posts nothing.
            engine().refire(key)
            assertThat(notificationManager.allNotifications).isEmpty()
        }

    // ---- reboot ----

    @Test
    fun `boot reset forgets FIRED-but-unacked deliveries, keeps ACKED and SNOOZED`() =
        runTest(dispatcher) {
            val fired = ReminderLedgerKey.Upcoming("a", 1, today().plusDays(1))
            val acked = ReminderLedgerKey.Upcoming("b", 1, today().plusDays(1))
            val snoozed = ReminderLedgerKey.Upcoming("c", 1, today().plusDays(1))
            ledger.mark(fired, ReminderFireState.FIRED, keepUntil = today().plusDays(1))
            ledger.mark(acked, ReminderFireState.ACKED, keepUntil = today().plusDays(1))
            ledger.mark(snoozed, ReminderFireState.SNOOZED, keepUntil = today().plusDays(1), snoozedUntilMillis = 42L)

            ledger.resetFiredForBoot()

            val all = ledger.all()
            assertThat(all.keys).containsExactly(acked, snoozed)
            assertThat(all[snoozed]?.snoozedUntilMillis).isEqualTo(42L)

            // Re-arming a past-due snooze rings right away (now + 1s), never in the past.
            snoozer.arm(snoozed, 42L)
            assertThat(alarmManager.scheduledAlarms.single().triggerAtTime).isEqualTo(clock.millis() + 1_000)
        }

    @Test
    fun `prune drops entries whose event day has passed`() =
        runTest(dispatcher) {
            val old = ReminderLedgerKey.Upcoming("old", 1, today().minusDays(1))
            val live = ReminderLedgerKey.Upcoming("live", 1, today().plusDays(1))
            ledger.mark(old, ReminderFireState.ACKED, keepUntil = today().minusDays(1))
            ledger.mark(live, ReminderFireState.ACKED, keepUntil = today().plusDays(1))

            ledger.prune(today())

            assertThat(ledger.all().keys).containsExactly(live)
        }
}
