package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.designsystem.component.SHEET_FOOTER_TEST_TAG
import com.itsluminous.samaroh.core.designsystem.component.SHEET_SCROLL_TEST_TAG
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.booking.FakeBookingColorCatalog
import com.itsluminous.samaroh.feature.booking.FakeEventTypeCatalog
import com.itsluminous.samaroh.feature.booking.domain.BookingCardActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * ADR-107: on a SMALL window a booking card with long notes, several payments AND an
 * expenses history is taller than the screen. The card must (1) keep its action row pinned
 * and tappable without any scrolling and (2) let the histories be reached by scrolling the
 * middle region — a bare `ModalBottomSheet { Column }` did neither (clipped, no scroll).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h560dp-xhdpi")
class BookingCardSheetScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private val booking =
        Fixtures
            .booking(startDate = LocalDate.of(2026, 11, 20), totalAmountPaise = 5_00_000_00L)
            .copy(
                // A custom label renders verbatim — no catalog lookup needed in the test.
                eventType = "Sangeet",
                customerName = "Sandhya Upadhyay",
                customerPhone = "9876543210",
                notes = (1..12).joinToString("\n") { "Line $it — stage on the east lawn, caterer arrives at 4 pm, 400 chairs." },
            )
    private val payments = (1..6).map { Fixtures.payment(booking.id, amountPaise = 20_000_00L, paidOn = LocalDate.of(2026, 10, it)) }
    private val expenses =
        (1..5).map {
            Fixtures
                .expense(
                    partyId = "party",
                    amountPaise = 1_500_00L,
                    direction = if (it % 2 == 0) ExpenseDirection.RECEIVED else ExpenseDirection.PAID,
                    expenseDate = LocalDate.of(2026, 10, it),
                ).copy(bookingId = booking.id, notes = "Decoration advance $it")
        }

    private fun string(res: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(res)

    private var recordPaymentTaps = 0

    private fun showCard() {
        compose.setContent {
            SamarohTheme {
                BookingCardSheet(
                    detail =
                        BookingDetail(
                            booking = booking,
                            payments = payments,
                            paidPaise = payments.sumOf { it.amountPaise },
                            duePaise = booking.totalAmountPaise - payments.sumOf { it.amountPaise },
                            creatorName = "Owner",
                            expenses = expenses,
                        ),
                    eventTypes = FakeEventTypeCatalog(),
                    presets = emptyList(),
                    bookingColors = FakeBookingColorCatalog(),
                    creatorName = "Owner",
                    actions =
                        BookingCardActions.forBooking(
                            status = BookingStatus.CONFIRMED,
                            isMarker = false,
                            canEdit = true,
                            canDelete = true,
                            canRecordPayment = true,
                            canInvoice = true,
                            canRecordExpense = true,
                        ),
                    canViewAmounts = true,
                    onDismiss = {},
                    onEdit = {},
                    onRecordPayment = { recordPaymentTaps++ },
                    onRecordExpense = {},
                    onInvoicePdf = {},
                    onInvoiceText = {},
                    onWhatsApp = {},
                    onCancelBooking = {},
                    onRestoreBooking = {},
                    onDeleteBooking = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `action row is pinned - displayed and clickable without scrolling`() {
        showCard()

        val recordPayment = compose.onNodeWithContentDescription(string(R.string.booking_card_action_record_payment))
        recordPayment.assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("booking_card_record_expense", useUnmergedTree = true).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription(string(R.string.booking_card_action_invoice)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.booking_card_action_whatsapp)).assertIsDisplayed()

        recordPayment.performClick()
        assertThat(recordPaymentTaps).isEqualTo(1)

        // The footer sits INSIDE the window: its bottom edge never exceeds the window height.
        val footer = compose.onNodeWithTag(SHEET_FOOTER_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertThat(footer.bottom.value).isAtMost(WINDOW_HEIGHT_DP + 0.5f)
    }

    @Test
    fun `content overflows the small window and scrolls - expenses total reachable by scroll`() {
        showCard()

        val scroll = compose.onNodeWithTag(SHEET_SCROLL_TEST_TAG, useUnmergedTree = true)
        scroll.assert(hasScrollAction())

        // Everything is composed (plain Column, not lazy) …
        compose.onAllNodesWithTag("booking_card_payment_row", useUnmergedTree = true).fetchSemanticsNodes().let {
            assertThat(it).hasSize(payments.size)
        }
        compose.onAllNodesWithTag("booking_card_expense_row", useUnmergedTree = true).fetchSemanticsNodes().let {
            assertThat(it).hasSize(expenses.size)
        }
        // … but the expenses total starts BELOW the fold of a 560dp window …
        val total = compose.onNodeWithTag("booking_card_expenses_total", useUnmergedTree = true)
        total.assertIsNotDisplayed()

        // … and scrolling the middle region brings it on screen while the footer stays put.
        val footerBefore = compose.onNodeWithTag(SHEET_FOOTER_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()
        total.performScrollTo()
        compose.waitForIdle()
        total.assertIsDisplayed()
        val footerAfter = compose.onNodeWithTag(SHEET_FOOTER_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertThat(footerAfter.top.value).isWithin(0.5f).of(footerBefore.top.value)
        compose.onNodeWithContentDescription(string(R.string.booking_card_action_record_payment)).assertIsDisplayed()

        // The scroll region is bounded by the window, not by its content.
        assertThat(scroll.getBoundsInRoot().height.value).isLessThan(WINDOW_HEIGHT_DP)
    }

    private companion object {
        /** Matches the `h560dp` qualifier on the class. */
        const val WINDOW_HEIGHT_DP = 560f
    }
}
