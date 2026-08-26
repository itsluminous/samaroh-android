package com.itsluminous.samaroh.e2e

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.testing.Fixtures
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * §13 acceptance 4: booking creation with auto-calculated due, calendar cell icons,
 * tentative 👤, empty-date prefill, record-payment updating the due, blocked dates.
 */
abstract class BookingFlowTest(
    localeTag: String,
) : LocalizedE2eTest(localeTag) {
    override suspend fun seed() {
        seedOnboardedBusiness()
    }

    private fun fillAmountField(
        label: String,
        value: String,
    ) {
        compose.onNode(hasSetTextAction() and hasText(label)).performTextInput(value)
    }

    private fun openAddBookingForm() {
        waitForContentDescription(string(R.string.booking_calendar_add)).performClick()
        waitForText(string(R.string.booking_form_customer_name))
    }

    @Test
    fun createBooking_totalAndAdvance_showsDueAndCalendarIcon() {
        openAddBookingForm()
        compose
            .onNode(hasSetTextAction() and hasText(string(R.string.booking_form_customer_name)))
            .performTextInput("Radha")
        fillAmountField(string(R.string.booking_form_total_amount), "200000")
        fillAmountField(string(R.string.booking_form_advance), "50000")

        // Due is auto-calculated and live: ₹2,00,000 − ₹50,000 = ₹1,50,000 (§4.1).
        waitForText(AmountFormatter.format(1_50_000_00L))

        compose.onNode(hasText(string(R.string.common_action_save))).performClick()

        // Back on the calendar: the booked cell shows the event icon as a translucent
        // watermark behind the date number, and the agenda lists the booking.
        waitForText("Radha", substring = true)
        waitForText("\uD83D\uDC92") // exact-match = the day cell (agenda text is longer)
    }

    @Test
    fun tentativeBooking_showsPersonIconOnCalendar() {
        openAddBookingForm()
        compose.onNode(hasText(string(R.string.booking_status_tentative))).performClick()
        compose
            .onNode(hasSetTextAction() and hasText(string(R.string.booking_form_customer_name)))
            .performTextInput("Sunil")
        compose.onNode(hasText(string(R.string.common_action_save))).performClick()

        // ADR-020: tentative bookings render 👤 everywhere regardless of event type.
        waitForText("Sunil", substring = true)
        waitForText("\uD83D\uDC64")
    }

    @Test
    fun emptyDateTap_prefillsStartAndEndDate() {
        val date = emptyMidMonthDate()
        waitForText(string(R.string.booking_summary_this_month))
        waitForContentDescription(formatFullDate(date), substring = true).performClick()

        // The Add form opens with the tapped date pre-selected as start AND end (§13.4).
        waitForText(string(R.string.booking_form_start_date), substring = true)
        compose.onAllNodesWithText(formatDate(date)).assertCountEquals(2)
    }

    @Test
    fun recordPayment_updatesDueToZero() {
        val booking =
            Fixtures
                .booking(startDate = futureDateInCurrentMonth(), totalAmountPaise = 2_00_000_00L)
                .copy(customerName = "Meera")
        runBlocking {
            bookingRepository.saveBooking(booking)
            bookingRepository.recordPayment(Fixtures.payment(booking.id, amountPaise = 50_000_00L, paidOn = LocalDate.now()))
        }

        // Tap the booked day cell (a11y description carries the booking label).
        waitForContentDescription("Meera", substring = true).performClick()

        // Booking card: due = ₹1,50,000, bold red (asserting the text).
        waitForText(AmountFormatter.format(1_50_000_00L))
        waitForContentDescription(string(R.string.booking_card_action_record_payment)).performClick()

        // Record-payment sheet prefills the due in rupees; saving settles the booking.
        waitForText(string(R.string.booking_payment_amount), substring = true)
        compose.onNode(hasText("150000")).performClick() // focus prefilled amount field
        compose.onNode(hasText(string(R.string.common_action_save))).performClick()

        waitForText(AmountFormatter.format(0L))
    }

    @Test
    fun blockDates_marksCellBlocked() {
        waitForText(string(R.string.booking_summary_this_month))
        waitForContentDescription(string(R.string.booking_calendar_more_options)).performClick()
        waitForText(string(R.string.booking_calendar_block_dates)).performClick()
        waitForText(string(R.string.booking_block_reason_label), substring = true)
        compose.onNode(hasText(string(R.string.common_action_save))).performClick()

        // The blocked (grey-striped) cell announces its state for TalkBack — the
        // reliable semantic proof the stripe rendering path is active.
        waitForContentDescription(string(R.string.booking_calendar_a11y_blocked_day), substring = true)
    }

    /** A current-month date guaranteed empty in these tests (today is never day 20 AND 21). */
    private fun emptyMidMonthDate(): LocalDate {
        val month = YearMonth.now()
        val candidate = month.atDay(20)
        return if (LocalDate.now() == candidate) month.atDay(21) else candidate
    }
}

@HiltAndroidTest
class BookingFlowEnTest : BookingFlowTest("en")

@HiltAndroidTest
class BookingFlowHiTest : BookingFlowTest("hi")
