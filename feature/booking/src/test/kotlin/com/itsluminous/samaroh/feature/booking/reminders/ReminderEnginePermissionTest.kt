package com.itsluminous.samaroh.feature.booking.reminders

import android.app.AlarmManager
import android.app.Notification
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
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.model.BookingPermissions
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderKind
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.booking.FakeActorProvider
import com.itsluminous.samaroh.feature.booking.FakeBookingRepository
import com.itsluminous.samaroh.feature.booking.FakeBusinessRepository
import com.itsluminous.samaroh.feature.booking.FakeCurrentUserProvider
import com.itsluminous.samaroh.feature.booking.FakeEventTypeRepository
import com.itsluminous.samaroh.feature.booking.domain.BookingActor
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * ADR-095/ADR-097 — reminders follow the signed-in member's permissions. The reported
 * bug: a viewer (no `booking.record_payment`) had payment-reminder rows planned on HIS
 * device and pushed to the server, where RLS rejected them forever. Rows are device-local
 * now, and a member without the permission behind a reminder's actions gets neither the
 * row nor the notification nor the alarm. ADR-097 tightens the payment rule: "Did X pay?"
 * needs BOTH `record_payment` AND `view_amounts` (owner always qualifies); lacking either
 * yields nothing, and revoking either retracts what was already delivered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReminderEnginePermissionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = Clock.fixed(Fixtures.NOW, ZoneId.of("Asia/Kolkata"))
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val dispatcher = UnconfinedTestDispatcher()
    private val storeScope = CoroutineScope(dispatcher + Job())
    private val emptyCatalog =
        object : EventTypeCatalog {
            override val eventTypes: List<BuiltInEventType> = emptyList()
        }

    private val bookingRepository = FakeBookingRepository()
    private val businessRepository = FakeBusinessRepository(listOf(Fixtures.business()))
    private val actorProvider = FakeActorProvider()

    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tmp.root, "settings.preferences_pb")
        }
    private val ledger = ReminderLedger(dataStore, FakeCurrentUserProvider("member-1"))
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
            actorProvider = actorProvider,
            clock = clock,
        )

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun posted(): List<Notification> = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private val alarmManager get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    private suspend fun selectNotificationStyle() {
        dataStore.edit { it[stringPreferencesKey("booking_reminder_style")] = ReminderStyle.NOTIFICATION.wire }
    }

    private fun member(permissions: BookingPermissions) {
        actorProvider.actor = BookingActor(userId = "member-1", displayName = "Prakash", isOwner = false, permissions = permissions)
    }

    /** The reported viewer: expenses-style read access, booking view only, no payment power. */
    private val viewer = BookingPermissions(view = true)

    /** Both ADR-097 keys: `record_payment` + `view_amounts` (absent = true). */
    private val payer = BookingPermissions(view = true, recordPayment = true)

    /** Can record but may not see amounts — gets NO payment reminders (ADR-097). */
    private val blindPayer = BookingPermissions(view = true, recordPayment = true, viewAmounts = false)

    /** Can see amounts but may not record — gets NO payment reminders (ADR-097). */
    private val amountViewer = BookingPermissions(view = true, viewAmounts = true)
    private val editor = BookingPermissions(view = true, edit = true)

    private suspend fun seedUnpaidEndedBooking() =
        Fixtures
            .booking(startDate = today().minusDays(3), endDate = today().minusDays(2), totalAmountPaise = 2_00_000_00L)
            .also {
                bookingRepository.saveBooking(it)
                bookingRepository.recordPayment(Fixtures.payment(bookingId = it.id, amountPaise = 50_000_00L))
            }

    private suspend fun seedDueFollowUp() {
        val tentative =
            Fixtures.booking(
                startDate = today().plusDays(10),
                endDate = today().plusDays(10),
                status = BookingStatus.TENTATIVE,
                totalAmountPaise = 0L,
            )
        bookingRepository.saveBooking(tentative)
        bookingRepository.saveReminder(
            PaymentReminder(
                id = UUID.randomUUID().toString(),
                bookingId = tentative.id,
                businessId = Fixtures.BUSINESS_ID,
                remindOn = today(),
                status = ReminderStatus.PENDING,
                amountDueSnapshotPaise = 0L,
                createdAt = Fixtures.NOW,
                updatedAt = Fixtures.NOW,
                kind = ReminderKind.FOLLOW_UP,
            ),
        )
    }

    // ---- payment reminders: booking.record_payment ----

    @Test
    fun `viewer without record_payment gets NO payment reminder row and NO notification`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            member(viewer)
            val booking = seedUnpaidEndedBooking()

            engine().runDailyPass()

            assertThat(bookingRepository.remindersForBooking(booking.id)).isEmpty()
            assertThat(posted()).isEmpty()
        }

    @Test
    fun `member with record_payment gets the payment reminder row and notification`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            member(payer)
            val booking = seedUnpaidEndedBooking()

            engine().runDailyPass()

            assertThat(bookingRepository.remindersForBooking(booking.id)).hasSize(1)
            assertThat(posted()).hasSize(1)
        }

    @Test
    fun `owner always gets payment reminders`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            val booking = seedUnpaidEndedBooking()

            engine().runDailyPass()

            assertThat(bookingRepository.remindersForBooking(booking.id)).hasSize(1)
            assertThat(posted()).hasSize(1)
        }

    @Test
    fun `record_payment WITHOUT view_amounts gets NO payment reminder row and NO notification`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            member(blindPayer)
            val booking = seedUnpaidEndedBooking()

            engine().runDailyPass()

            assertThat(bookingRepository.remindersForBooking(booking.id)).isEmpty()
            assertThat(posted()).isEmpty()
            assertThat(alarmManager.scheduledAlarms).isEmpty()
        }

    @Test
    fun `view_amounts WITHOUT record_payment gets NO payment reminder row and NO notification`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            member(amountViewer)
            val booking = seedUnpaidEndedBooking()

            engine().runDailyPass()

            assertThat(bookingRepository.remindersForBooking(booking.id)).isEmpty()
            assertThat(posted()).isEmpty()
        }

    @Test
    fun `payment reminder always shows the real due amount - there is no masked variant`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            member(payer)
            seedUnpaidEndedBooking()

            engine().runDailyPass()

            val text =
                posted()
                    .single()
                    .extras
                    .getCharSequence(Notification.EXTRA_TEXT)
                    .toString()
            assertThat(text).contains(AmountFormatter.format(1_50_000_00L))
            assertThat(text).doesNotContain(AmountFormatter.MASKED)
        }

    @Test
    fun `full-screen payment reminder is also withheld without view_amounts`() =
        runTest(dispatcher) {
            dataStore.edit { it[stringPreferencesKey("booking_reminder_style")] = ReminderStyle.FULLSCREEN.wire }
            member(blindPayer)
            seedUnpaidEndedBooking()

            engine().runDailyPass()

            assertThat(posted()).isEmpty()
            assertThat(alarmManager.scheduledAlarms).isEmpty()
        }

    // ---- follow-ups: booking.edit ----

    @Test
    fun `follow-up reminder requires booking edit`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            member(viewer)
            seedDueFollowUp()

            engine().runDailyPass()
            assertThat(posted()).isEmpty()

            member(editor)
            engine().runDailyPass()
            assertThat(posted()).hasSize(1)
        }

    // ---- upcoming-event reminders: booking.view ----

    @Test
    fun `upcoming reminder requires booking view`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            bookingRepository.saveBooking(Fixtures.booking(startDate = today().plusDays(1), endDate = today().plusDays(1)))

            member(BookingPermissions(view = false))
            engine().runDailyPass()
            assertThat(posted()).isEmpty()

            member(viewer)
            engine().runDailyPass()
            assertThat(posted()).hasSize(1)
        }

    // ---- snooze re-fire re-checks the grant ----

    @Test
    fun `a snoozed payment reminder is dropped on refire when record_payment was revoked`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            member(payer)
            val booking = seedUnpaidEndedBooking()
            engine().runDailyPass()
            val reminder = bookingRepository.remindersForBooking(booking.id).single()
            val key = ReminderLedgerKey.Row(reminder.id, booking.id, followUp = false)
            context.getSystemService(NotificationManager::class.java).cancelAll()
            ledger.mark(key, ReminderFireState.SNOOZED, keepUntil = today().plusDays(1), snoozedUntilMillis = clock.millis())

            member(viewer)
            engine().refire(key)

            assertThat(posted()).isEmpty()
            assertThat(ledger.entry(key)).isNull()
        }

    @Test
    fun `a snoozed payment reminder is dropped on refire when view_amounts was revoked`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            member(payer)
            val booking = seedUnpaidEndedBooking()
            engine().runDailyPass()
            val reminder = bookingRepository.remindersForBooking(booking.id).single()
            val key = ReminderLedgerKey.Row(reminder.id, booking.id, followUp = false)
            context.getSystemService(NotificationManager::class.java).cancelAll()
            ledger.mark(key, ReminderFireState.SNOOZED, keepUntil = today().plusDays(1), snoozedUntilMillis = clock.millis())

            member(blindPayer)
            engine().refire(key)

            assertThat(posted()).isEmpty()
            assertThat(ledger.entry(key)).isNull()
        }

    // ---- revocation: the next planning pass retracts delivered payment reminders ----

    @Test
    fun `revoking view_amounts cancels the posted payment notification and clears its ledger entry`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            member(payer)
            val booking = seedUnpaidEndedBooking()
            engine().runDailyPass()
            val reminder = bookingRepository.remindersForBooking(booking.id).single()
            val key = ReminderLedgerKey.Row(reminder.id, booking.id, followUp = false)
            assertThat(posted()).hasSize(1)
            assertThat(ledger.entry(key)?.state).isEqualTo(ReminderFireState.FIRED)

            member(blindPayer)
            engine().runDailyPass()

            assertThat(posted()).isEmpty()
            assertThat(ledger.entry(key)).isNull()
            // The device-local row is kept (not dismissed): the card filters it; a
            // re-grant delivers it afresh.
            assertThat(bookingRepository.remindersForBooking(booking.id).single().status).isEqualTo(ReminderStatus.PENDING)
        }

    @Test
    fun `revoking record_payment disarms a pending snooze and clears its ledger entry`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            member(payer)
            val booking = seedUnpaidEndedBooking()
            engine().runDailyPass()
            val reminder = bookingRepository.remindersForBooking(booking.id).single()
            val key = ReminderLedgerKey.Row(reminder.id, booking.id, followUp = false)
            context.getSystemService(NotificationManager::class.java).cancelAll()
            snoozer.snooze(key, SnoozePreset.ONE_HOUR, keepUntil = today().plusDays(1))
            assertThat(alarmManager.scheduledAlarms).hasSize(1)
            assertThat(ledger.entry(key)?.state).isEqualTo(ReminderFireState.SNOOZED)

            member(amountViewer)
            engine().runDailyPass()

            assertThat(alarmManager.scheduledAlarms).isEmpty()
            assertThat(ledger.entry(key)).isNull()
            assertThat(posted()).isEmpty()
        }

    @Test
    fun `revocation leaves follow-up and upcoming deliveries alone`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            member(payer.copy(edit = true))
            val booking = seedUnpaidEndedBooking()
            seedDueFollowUp()
            bookingRepository.saveBooking(Fixtures.booking(startDate = today().plusDays(1), endDate = today().plusDays(1)))
            engine().runDailyPass()
            assertThat(posted()).hasSize(3) // payment + follow-up + upcoming
            val paymentKey =
                ReminderLedgerKey.Row(bookingRepository.remindersForBooking(booking.id).single().id, booking.id, followUp = false)

            member(blindPayer.copy(edit = true))
            engine().runDailyPass()

            assertThat(posted()).hasSize(2)
            assertThat(ledger.entry(paymentKey)).isNull()
            assertThat(
                ledger
                    .all()
                    .keys
                    .filterIsInstance<ReminderLedgerKey.Row>()
                    .count { it.followUp },
            ).isEqualTo(1)
            assertThat(ledger.all().keys.filterIsInstance<ReminderLedgerKey.Upcoming>()).hasSize(1)
        }

    @Test
    fun `re-granting both keys delivers the payment reminder again`() =
        runTest(dispatcher) {
            selectNotificationStyle()
            member(payer)
            val booking = seedUnpaidEndedBooking()
            engine().runDailyPass()
            member(blindPayer)
            engine().runDailyPass()
            assertThat(posted()).isEmpty()

            member(payer)
            engine().runDailyPass()

            assertThat(posted()).hasSize(1)
            assertThat(bookingRepository.remindersForBooking(booking.id)).hasSize(1) // same row, no duplicate
        }
}
