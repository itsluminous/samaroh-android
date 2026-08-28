package com.itsluminous.samaroh.feature.booking.ui.form

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingPermissions
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.ReminderKind
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.booking.FakeActorProvider
import com.itsluminous.samaroh.feature.booking.FakeBookingColorCatalog
import com.itsluminous.samaroh.feature.booking.FakeBookingRepository
import com.itsluminous.samaroh.feature.booking.FakeBusinessRepository
import com.itsluminous.samaroh.feature.booking.FakeEventTypeRepository
import com.itsluminous.samaroh.feature.booking.FakeFormFieldPrefs
import com.itsluminous.samaroh.feature.booking.RecordingSyncScheduler
import com.itsluminous.samaroh.feature.booking.domain.BookingActor
import com.itsluminous.samaroh.feature.booking.seededPresetFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Booking form logic (§4.1): prefilled tap-to-add, validation, non-blocking conflict
 * warning, blocking blocked-dates popup with owner override, advance → first payment.
 */
class BookingFormViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC)
    private val today: LocalDate = LocalDate.of(2026, 8, 25)

    private val repository = FakeBookingRepository()
    private val businessRepository = FakeBusinessRepository(listOf(Fixtures.business()))
    private val eventTypeRepository = FakeEventTypeRepository(seededPresetFixtures())
    private val syncScheduler = RecordingSyncScheduler()
    private val actorProvider = FakeActorProvider()
    private val fieldPrefs = FakeFormFieldPrefs()

    private fun viewModel(
        bookingId: String? = null,
        date: LocalDate? = null,
    ) = BookingFormViewModel(
        savedStateHandle = SavedStateHandle(mapOf("bookingId" to (bookingId ?: ""), "date" to (date?.toString() ?: ""))),
        bookingRepository = repository,
        businessRepository = businessRepository,
        actorProvider = actorProvider,
        eventTypeRepository = eventTypeRepository,
        bookingColorsProvider = FakeBookingColorCatalog(),
        syncScheduler = syncScheduler,
        fieldPrefs = fieldPrefs,
        clock = clock,
    )

    @Test
    fun `tapped empty date prefills start AND end date`() =
        runTest {
            val tapped = LocalDate.of(2026, 9, 14)
            val vm = viewModel(date = tapped)
            val state = vm.state.value
            assertThat(state.startDate).isEqualTo(tapped)
            assertThat(state.endDate).isEqualTo(tapped)
        }

    @Test
    fun `customer name is required`() =
        runTest {
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.save()
                assertThat(awaitItemMatching { it.blocker != null }.blocker).isEqualTo(FormBlocker.NameRequired)
                assertThat(repository.bookings.value).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `end date cannot precede start date`() =
        runTest {
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.setCustomerName("Asha")
                vm.setEndDate(
                    vm.state.value.startDate
                        .minusDays(2),
                )
                vm.save()
                assertThat(awaitItemMatching { it.blocker != null }.blocker).isEqualTo(FormBlocker.EndBeforeStart)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `conflict warning is non-blocking and counts other bookings`() =
        runTest {
            repository.conflictCounts = mapOf(today to 2)
            val vm = viewModel(date = today)
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.setCustomerName("Asha")
                vm.save()
                // Warned, not saved.
                assertThat(awaitItemMatching { it.blocker != null }.blocker).isEqualTo(FormBlocker.Conflict(2))
                assertThat(repository.bookings.value).isEmpty()

                // ★ "Save anyway": halls can host multiple events (§4.1).
                vm.saveAnyway()
                assertThat(awaitItemMatching { it.saved }.saved).isTrue()
                assertThat(repository.bookings.value).hasSize(1)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `editing a booking does not conflict with itself`() =
        runTest {
            val existing = Fixtures.booking(startDate = today)
            repository.bookings.value = listOf(existing)
            val vm = viewModel(bookingId = existing.id)
            vm.state.test {
                awaitItemMatching { it.loaded && it.editingId == existing.id }
                vm.save()
                val done = awaitItemMatching { it.saved || it.blocker != null }
                assertThat(done.blocker).isNull()
                assertThat(done.saved).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `blocked dates block with owner override`() =
        runTest {
            repository.blocks.value =
                listOf(
                    DateBlock(
                        id = "block-1",
                        businessId = Fixtures.BUSINESS_ID,
                        startDate = today,
                        endDate = today,
                        reason = null,
                        createdBy = Fixtures.USER_ID,
                        createdAt = Fixtures.NOW,
                        updatedAt = Fixtures.NOW,
                    ),
                )
            val vm = viewModel(date = today)
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.setCustomerName("Asha")
                vm.save()
                val blocked = awaitItemMatching { it.blocker != null }.blocker
                assertThat(blocked).isEqualTo(FormBlocker.BlockedDates(canOverride = true))
                assertThat(repository.bookings.value).isEmpty()

                vm.saveDespiteBlock()
                assertThat(awaitItemMatching { it.saved }.saved).isTrue()
                assertThat(repository.bookings.value).hasSize(1)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `non-owner cannot override blocked dates`() =
        runTest {
            actorProvider.actor =
                BookingActor(
                    userId = "employee",
                    displayName = "employee",
                    isOwner = false,
                    permissions = BookingPermissions(view = true, create = true),
                )
            repository.blocks.value =
                listOf(
                    DateBlock(
                        id = "block-1",
                        businessId = Fixtures.BUSINESS_ID,
                        startDate = today,
                        endDate = today,
                        reason = null,
                        createdBy = Fixtures.USER_ID,
                        createdAt = Fixtures.NOW,
                        updatedAt = Fixtures.NOW,
                    ),
                )
            val vm = viewModel(date = today)
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.setCustomerName("Asha")
                vm.save()
                assertThat(awaitItemMatching { it.blocker != null }.blocker)
                    .isEqualTo(FormBlocker.BlockedDates(canOverride = false))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `advance creates the first payment row dated today and money stays in paise`() =
        runTest {
            val vm = viewModel(date = today)
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.setCustomerName("Asha Devi")
                vm.setTotalAmount("2,00,000")
                vm.setAdvance("50000")
                // Live due preview: 2,00,000 − 50,000 = ₹1,50,000 in paise.
                assertThat(vm.state.value.duePaise).isEqualTo(1_50_000_00L)

                vm.save()
                awaitItemMatching { it.saved }

                val booking = repository.bookings.value.single()
                assertThat(booking.totalAmountPaise).isEqualTo(2_00_000_00L)
                assertThat(booking.status).isEqualTo(BookingStatus.CONFIRMED)
                val advance = repository.payments.value.single()
                assertThat(advance.amountPaise).isEqualTo(50_000_00L)
                assertThat(advance.paidOn).isEqualTo(today)
                assertThat(advance.bookingId).isEqualTo(booking.id)
                assertThat(syncScheduler.immediateSyncs).isAtLeast(1)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `editing keeps identity fields and stamps updatedBy`() =
        runTest {
            val existing = Fixtures.booking(startDate = today)
            repository.bookings.value = listOf(existing)
            val vm = viewModel(bookingId = existing.id)
            vm.state.test {
                awaitItemMatching { it.loaded && it.editingId == existing.id }
                vm.setCustomerName("New Name")
                vm.save()
                awaitItemMatching { it.saved }
                val updated = repository.bookings.value.single()
                assertThat(updated.id).isEqualTo(existing.id)
                assertThat(updated.createdBy).isEqualTo(existing.createdBy)
                assertThat(updated.createdAt).isEqualTo(existing.createdAt)
                assertThat(updated.updatedBy).isEqualTo("test-user")
                assertThat(updated.customerName).isEqualTo("New Name")
                // No advance row on edit.
                assertThat(repository.payments.value).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `rupee text parses to paise including decimals`() {
        assertThat(parseRupeesToPaise("1,06,511")).isEqualTo(1_06_511_00L)
        assertThat(parseRupeesToPaise("1200.50")).isEqualTo(1_200_50L)
        assertThat(parseRupeesToPaise("")).isEqualTo(0L)
        assertThat(parseRupeesToPaise("abc")).isEqualTo(0L)
    }

    // ---- manual invoice number (ADR-020) ----

    @Test
    fun `manual invoice number persists on save`() =
        runTest {
            val vm = viewModel(date = today)
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.setCustomerName("Asha")
                vm.setInvoiceNumber(" INV-CUSTOM-7 ")
                vm.save()
                awaitItemMatching { it.saved }
                assertThat(
                    repository.bookings.value
                        .single()
                        .invoiceNumber,
                ).isEqualTo("INV-CUSTOM-7")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `duplicate manual invoice number blocks the save`() =
        runTest {
            repository.bookings.value =
                listOf(Fixtures.booking(startDate = today.plusDays(30)).copy(invoiceNumber = "INV-CUSTOM-7"))
            val vm = viewModel(date = today)
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.setCustomerName("Asha")
                vm.setInvoiceNumber("INV-CUSTOM-7")
                vm.save()
                assertThat(awaitItemMatching { it.blocker != null }.blocker)
                    .isEqualTo(FormBlocker.DuplicateInvoiceNumber)
                assertThat(repository.bookings.value).hasSize(1)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `blank manual invoice number leaves the booking unnumbered`() =
        runTest {
            val vm = viewModel(date = today)
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.setCustomerName("Asha")
                vm.save()
                awaitItemMatching { it.saved }
                assertThat(
                    repository.bookings.value
                        .single()
                        .invoiceNumber,
                ).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `frozen invoice number is exposed and never overwritten`() =
        runTest {
            val existing = Fixtures.booking(startDate = today).copy(invoiceNumber = "INV-2026-0001")
            repository.bookings.value = listOf(existing)
            val vm = viewModel(bookingId = existing.id)
            vm.state.test {
                val loaded = awaitItemMatching { it.loaded && it.editingId == existing.id }
                assertThat(loaded.frozenInvoiceNumber).isEqualTo("INV-2026-0001")
                // A stray edit of the text field must not touch the frozen number.
                vm.setInvoiceNumber("INV-HACK")
                vm.save()
                awaitItemMatching { it.saved }
                assertThat(
                    repository.bookings.value
                        .single()
                        .invoiceNumber,
                ).isEqualTo("INV-2026-0001")
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- tentative follow-up (ADR-020) ----

    @Test
    fun `saving a tentative booking creates a follow-up reminder at today plus N`() =
        runTest {
            val vm = viewModel(date = today)
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.setCustomerName("Asha")
                vm.setStatus(BookingStatus.TENTATIVE)
                vm.selectFollowUpPreset(7)
                vm.save()
                awaitItemMatching { it.saved }
                val reminder = repository.reminders.value.single()
                assertThat(reminder.kind).isEqualTo(ReminderKind.FOLLOW_UP)
                assertThat(reminder.status).isEqualTo(ReminderStatus.PENDING)
                assertThat(reminder.remindOn).isEqualTo(today.plusDays(7))
                assertThat(reminder.bookingId).isEqualTo(
                    repository.bookings.value
                        .single()
                        .id,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `custom follow-up day count is honoured`() =
        runTest {
            val vm = viewModel(date = today)
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.setCustomerName("Asha")
                vm.setStatus(BookingStatus.TENTATIVE)
                vm.selectFollowUpCustom()
                vm.setFollowUpCustomText("12")
                vm.save()
                awaitItemMatching { it.saved }
                assertThat(
                    repository.reminders.value
                        .single()
                        .remindOn,
                ).isEqualTo(today.plusDays(12))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `re-saving as confirmed dismisses the pending follow-up`() =
        runTest {
            val existing = Fixtures.booking(startDate = today, status = BookingStatus.TENTATIVE)
            repository.bookings.value = listOf(existing)
            repository.reminders.value =
                listOf(
                    com.itsluminous.samaroh.feature.booking.domain.TentativeFollowUpPlanner
                        .create(existing, 3, today, { "follow-up-1" }, Fixtures.NOW),
                )
            val vm = viewModel(bookingId = existing.id)
            vm.state.test {
                awaitItemMatching { it.loaded && it.editingId == existing.id }
                vm.setStatus(BookingStatus.CONFIRMED)
                vm.save()
                awaitItemMatching { it.saved }
                assertThat(
                    repository.bookings.value
                        .single()
                        .status,
                ).isEqualTo(BookingStatus.CONFIRMED)
                assertThat(
                    repository.reminders.value
                        .single()
                        .status,
                ).isEqualTo(ReminderStatus.DISMISSED)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `confirmed bookings create no follow-up`() =
        runTest {
            val vm = viewModel(date = today)
            vm.state.test {
                awaitItemMatching { it.loaded }
                vm.setCustomerName("Asha")
                vm.save()
                awaitItemMatching { it.saved }
                assertThat(repository.reminders.value).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- field visibility prefs (ADR-020) ----

    @Test
    fun `field visibility defaults hide only the security deposit`() =
        runTest {
            val vm = viewModel()
            vm.state.test {
                val state = awaitItemMatching { it.loaded }
                assertThat(state.fieldVisibility.showSecurityDeposit).isFalse()
                assertThat(state.fieldVisibility.showSource).isTrue()
                assertThat(state.fieldVisibility.showTimes).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `field visibility changes flow into the form state`() =
        runTest {
            val vm = viewModel()
            vm.state.test {
                awaitItemMatching { it.loaded }
                fieldPrefs.state.value =
                    com.itsluminous.samaroh.feature.booking.ui.form
                        .BookingFormFieldVisibility(showSecurityDeposit = true, showSource = false, showTimes = false)
                val state = awaitItemMatching { it.fieldVisibility.showSecurityDeposit }
                assertThat(state.fieldVisibility.showSource).isFalse()
                assertThat(state.fieldVisibility.showTimes).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `hidden deposit field still preserves the stored value on edit`() =
        runTest {
            val existing = Fixtures.booking(startDate = today, securityDepositPaise = 25_000_00L)
            repository.bookings.value = listOf(existing)
            val vm = viewModel(bookingId = existing.id)
            vm.state.test {
                awaitItemMatching { it.loaded && it.editingId == existing.id }
                vm.setCustomerName("New Name")
                vm.save()
                awaitItemMatching { it.saved }
                assertThat(
                    repository.bookings.value
                        .single()
                        .securityDepositPaise,
                ).isEqualTo(25_000_00L)
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
