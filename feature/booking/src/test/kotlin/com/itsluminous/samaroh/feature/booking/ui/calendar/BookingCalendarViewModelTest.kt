package com.itsluminous.samaroh.feature.booking.ui.calendar

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.PaymentMethod
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.booking.FakeActorProvider
import com.itsluminous.samaroh.feature.booking.FakeBookingCalendarPrefs
import com.itsluminous.samaroh.feature.booking.FakeBookingColorCatalog
import com.itsluminous.samaroh.feature.booking.FakeBookingRepository
import com.itsluminous.samaroh.feature.booking.FakeBusinessRepository
import com.itsluminous.samaroh.feature.booking.FakeEventTypeCatalog
import com.itsluminous.samaroh.feature.booking.FakeEventTypeRepository
import com.itsluminous.samaroh.feature.booking.FakeInvoiceGenerator
import com.itsluminous.samaroh.feature.booking.RecordingSyncScheduler
import com.itsluminous.samaroh.feature.booking.seededPresetFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Calendar ViewModel behavior (§4.1): month summary, payment recording (+ reminder
 * chaining), cancellation, tap routing, invoice wiring.
 */
class BookingCalendarViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Fixtures.NOW = 2026-08-25T09:00Z → "today" is 2026-08-25 in UTC.
    private val clock: Clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC)
    private val today: LocalDate = LocalDate.of(2026, 8, 25)

    private val repository = FakeBookingRepository()
    private val businessRepository = FakeBusinessRepository(listOf(Fixtures.business()))
    private val eventTypeRepository = FakeEventTypeRepository(seededPresetFixtures())
    private val invoiceGenerator = FakeInvoiceGenerator()
    private val syncScheduler = RecordingSyncScheduler()
    private val calendarPrefs = FakeBookingCalendarPrefs()

    private fun viewModel() =
        BookingCalendarViewModel(
            bookingRepository = repository,
            businessRepository = businessRepository,
            actorProvider = FakeActorProvider(),
            invoiceGenerator = invoiceGenerator,
            syncScheduler = syncScheduler,
            eventTypeRepository = eventTypeRepository,
            eventTypesProvider = FakeEventTypeCatalog(),
            bookingColorsProvider = FakeBookingColorCatalog(),
            calendarPrefs = calendarPrefs,
            clock = clock,
        )

    private fun pendingReminder(
        bookingId: String,
        remindOn: LocalDate = today,
        duePaise: Long = 1_50_000_00L,
    ) = PaymentReminder(
        id = "reminder-$bookingId",
        bookingId = bookingId,
        businessId = Fixtures.BUSINESS_ID,
        remindOn = remindOn,
        status = ReminderStatus.PENDING,
        amountDueSnapshotPaise = duePaise,
        createdAt = Fixtures.NOW,
        updatedAt = Fixtures.NOW,
    )

    @Test
    fun `month summary sums received and pending over the shown month`() =
        runTest {
            // Booking in the current month (Aug 2026): total ₹2,00,000, paid ₹50,000.
            val booking = Fixtures.booking(startDate = LocalDate.of(2026, 8, 28))
            repository.bookings.value = listOf(booking)
            repository.payments.value = listOf(Fixtures.payment(booking.id, amountPaise = 50_000_00L))

            viewModel().uiState.test {
                val state = awaitItemMatching { it.loaded && it.grid != null }
                assertThat(state.receivedPaise).isEqualTo(50_000_00L)
                assertThat(state.pendingPaise).isEqualTo(1_50_000_00L)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `cancelled bookings do not count toward the summary but stay in the agenda`() =
        runTest {
            val cancelled =
                Fixtures.booking(startDate = LocalDate.of(2026, 8, 28), status = BookingStatus.CANCELLED)
            repository.bookings.value = listOf(cancelled)

            viewModel().uiState.test {
                val state = awaitItemMatching { it.loaded && it.grid != null }
                assertThat(state.receivedPaise).isEqualTo(0L)
                assertThat(state.pendingPaise).isEqualTo(0L)
                assertThat(state.agenda.map { it.booking.id }).containsExactly(cancelled.id)
                assertThat(
                    state.grid!!
                        .weeks
                        .flatMap { it.days }
                        .flatMap { it.eventIcons },
                ).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `recordPayment appends an immutable payment row`() =
        runTest {
            val booking = Fixtures.booking(startDate = today)
            repository.bookings.value = listOf(booking)
            val vm = viewModel()
            vm.uiState.test {
                awaitItemMatching { it.loaded && it.actor != null }

                vm.recordPayment(booking.id, 25_000_00L, today, PaymentMethod.UPI, notes = "part")

                assertThat(repository.payments.value).hasSize(1)
                val payment = repository.payments.value.single()
                assertThat(payment.amountPaise).isEqualTo(25_000_00L)
                assertThat(payment.method).isEqualTo(PaymentMethod.UPI)
                assertThat(payment.bookingId).isEqualTo(booking.id)
                assertThat(syncScheduler.immediateSyncs).isAtLeast(1)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `partial payment answering a reminder confirms it and chains plus 7 days`() =
        runTest {
            val booking = Fixtures.booking(startDate = today.minusDays(5), endDate = today.minusDays(2))
            repository.bookings.value = listOf(booking)
            val reminder = pendingReminder(booking.id)
            repository.reminders.value = listOf(reminder)

            val vm = viewModel()
            vm.uiState.test {
                awaitItemMatching { it.loaded && it.actor != null }

                vm.recordPayment(booking.id, 50_000_00L, today, PaymentMethod.CASH, null, answeringReminderId = reminder.id)

                val reminders = repository.reminders.value
                val answered = reminders.first { it.id == reminder.id }
                assertThat(answered.status).isEqualTo(ReminderStatus.CONFIRMED)
                val next = reminders.first { it.id != reminder.id }
                assertThat(next.status).isEqualTo(ReminderStatus.PENDING)
                assertThat(next.remindOn).isEqualTo(today.plusDays(7))
                assertThat(next.amountDueSnapshotPaise).isEqualTo(1_50_000_00L) // 2,00,000 − 50,000
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `full payment confirms all pending reminders without chaining`() =
        runTest {
            val booking = Fixtures.booking(startDate = today.minusDays(5), endDate = today.minusDays(2))
            repository.bookings.value = listOf(booking)
            repository.reminders.value = listOf(pendingReminder(booking.id))

            val vm = viewModel()
            vm.uiState.test {
                awaitItemMatching { it.loaded && it.actor != null }

                vm.recordPayment(booking.id, booking.totalAmountPaise, today, PaymentMethod.CASH, null)

                val reminders = repository.reminders.value
                assertThat(reminders).hasSize(1)
                assertThat(reminders.single().status).isEqualTo(ReminderStatus.CONFIRMED)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `cancelBooking releases the date and dismisses pending reminders`() =
        runTest {
            val booking = Fixtures.booking(startDate = today.minusDays(5), endDate = today.minusDays(2))
            repository.bookings.value = listOf(booking)
            repository.reminders.value = listOf(pendingReminder(booking.id))

            val vm = viewModel()
            vm.uiState.test {
                awaitItemMatching { it.loaded && it.actor != null }

                vm.cancelBooking(booking.id)

                assertThat(
                    repository.bookings.value
                        .single()
                        .status,
                ).isEqualTo(BookingStatus.CANCELLED)
                assertThat(
                    repository.bookings.value
                        .single()
                        .updatedBy,
                ).isEqualTo("test-user")
                assertThat(
                    repository.reminders.value
                        .single()
                        .status,
                ).isEqualTo(ReminderStatus.DISMISSED)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `confirmFullPayment records the outstanding due dated today`() =
        runTest {
            val booking = Fixtures.booking(startDate = today.minusDays(5), endDate = today.minusDays(2))
            repository.bookings.value = listOf(booking)
            val earlier = Fixtures.payment(booking.id, amountPaise = 50_000_00L)
            repository.payments.value = listOf(earlier)
            val reminder = pendingReminder(booking.id)
            repository.reminders.value = listOf(reminder)

            val vm = viewModel()
            vm.uiState.test {
                awaitItemMatching { it.loaded && it.actor != null }

                vm.confirmFullPayment(PendingConfirmationUi(reminder, booking))

                assertThat(repository.payments.value.sumOf { it.amountPaise }).isEqualTo(booking.totalAmountPaise)
                assertThat(
                    repository.reminders.value
                        .single()
                        .status,
                ).isEqualTo(ReminderStatus.CONFIRMED)
                val recorded = repository.payments.value.single { it.id != earlier.id }
                assertThat(recorded.paidOn).isEqualTo(today)
                assertThat(recorded.amountPaise).isEqualTo(1_50_000_00L)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `day tap routes to bookings block or add form`() =
        runTest {
            val bookedDate = LocalDate.of(2026, 8, 28)
            val blockedDate = LocalDate.of(2026, 8, 30)
            val booking = Fixtures.booking(startDate = bookedDate)
            repository.bookings.value = listOf(booking)
            repository.blocks.value =
                listOf(
                    DateBlock(
                        id = "block-1",
                        businessId = Fixtures.BUSINESS_ID,
                        startDate = blockedDate,
                        endDate = blockedDate,
                        reason = null,
                        createdBy = Fixtures.USER_ID,
                        createdAt = Fixtures.NOW,
                        updatedAt = Fixtures.NOW,
                    ),
                )

            val vm = viewModel()
            vm.uiState.test {
                awaitItemMatching { it.loaded && it.bookings.isNotEmpty() && it.blocks.isNotEmpty() }

                assertThat(vm.onDayTapped(bookedDate)).isEqualTo(DayTapResult.ShowBookings(listOf(booking.id)))
                assertThat(vm.onDayTapped(blockedDate)).isInstanceOf(DayTapResult.ShowBlock::class.java)
                // Empty date → Add form prefilled with the tapped date as start AND end (§4.1).
                assertThat(vm.onDayTapped(LocalDate.of(2026, 8, 20)))
                    .isEqualTo(DayTapResult.AddBooking(LocalDate.of(2026, 8, 20)))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `invoice actions call the frozen InvoiceGenerator contract`() =
        runTest {
            val booking = Fixtures.booking(startDate = today)
            repository.bookings.value = listOf(booking)
            val vm = viewModel()

            vm.eventFlow.test {
                vm.shareInvoicePdf(booking.id)
                assertThat(awaitItem()).isEqualTo(BookingEvent.SharePdf("/tmp/invoice-test.pdf"))

                vm.shareInvoiceText(booking.id)
                assertThat(awaitItem()).isEqualTo(BookingEvent.ShareText("invoice-text"))

                invoiceGenerator.pdfResult = Result.failure(IllegalStateException())
                vm.shareInvoicePdf(booking.id)
                assertThat(awaitItem()).isEqualTo(BookingEvent.InvoiceFailed)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(invoiceGenerator.pdfRequests).containsExactly(booking.id, booking.id)
            assertThat(invoiceGenerator.textRequests).containsExactly(booking.id)
        }

    @Test
    fun `icon watermark alpha follows the settings preference reactively`() =
        runTest {
            viewModel().iconWatermarkAlpha.test {
                assertThat(awaitItem()).isEqualTo(0.45f)

                calendarPrefs.alpha.value = 0.8f
                assertThat(awaitItem()).isEqualTo(0.8f)

                calendarPrefs.alpha.value = 0.2f
                assertThat(awaitItem()).isEqualTo(0.2f)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- events (full agenda) view ----

    @Test
    fun `events view toggle persists through the calendar prefs`() =
        runTest {
            val vm = viewModel()
            vm.eventsView.test {
                assertThat(awaitItem()).isFalse()

                vm.setEventsView(true)
                assertThat(awaitItem()).isTrue()
                assertThat(calendarPrefs.eventsViewState.value).isTrue()

                vm.setEventsView(false)
                assertThat(awaitItem()).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `events agenda groups the window by date and flags older bookings`() =
        runTest {
            // Inside the initial window (Jun–Dec 2026 around today 2026-08-25)...
            val todayBooking = Fixtures.booking(id = "b-today", startDate = today)
            val nextMonth = Fixtures.booking(id = "b-next", startDate = LocalDate.of(2026, 9, 10))
            // ...and one far in the past, outside it.
            val old = Fixtures.booking(id = "b-old", startDate = LocalDate.of(2026, 1, 15))
            repository.bookings.value = listOf(nextMonth, todayBooking, old)
            calendarPrefs.eventsViewState.value = true

            val vm = viewModel()
            vm.eventsAgenda.test {
                val state = awaitItemMatching { it.loaded && it.days.isNotEmpty() }
                assertThat(state.days.map { it.date }).containsExactly(today, LocalDate.of(2026, 9, 10)).inOrder()
                assertThat(state.hasMorePast).isTrue()
                assertThat(state.hasMoreFuture).isFalse()

                // Nearing the top edge loads the older window until the oldest booking is in.
                vm.loadOlderEvents()
                val expanded = awaitItemMatching { it.days.size == 3 }
                assertThat(
                    expanded.days
                        .first()
                        .bookings
                        .single()
                        .id,
                ).isEqualTo("b-old")
                assertThat(expanded.hasMorePast).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `booking card opens from events view for bookings outside the shown month`() =
        runTest {
            val far = Fixtures.booking(id = "b-far", startDate = LocalDate.of(2026, 11, 20))
            repository.bookings.value = listOf(far)

            val vm = viewModel()
            vm.detail.test {
                assertThat(awaitItem()).isNull()
                vm.openBooking("b-far")
                assertThat(awaitItemMatching { it != null }?.booking?.id).isEqualTo("b-far")
                cancelAndIgnoreRemainingEvents()
            }
        }
}

/** Awaits until the state stream emits an item satisfying [predicate]. */
private suspend fun <T> app.cash.turbine.TurbineTestContext<T>.awaitItemMatching(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
