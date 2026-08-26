package com.itsluminous.samaroh.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.testing.Fixtures
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * §11 W2-B cross-feature wiring: booking-card invoice share (chooser stubbed via
 * Espresso-Intents), the prefilled WhatsApp payment reminder, app-bar sync icon →
 * Sync status, and Menu → Reports.
 */
abstract class CrossFeatureIntentsTest(
    localeTag: String,
) : LocalizedE2eTest(localeTag) {
    private lateinit var booking: com.itsluminous.samaroh.core.model.Booking

    override suspend fun seed() {
        seedOnboardedBusiness()
        booking =
            Fixtures
                .booking(startDate = futureDateInCurrentMonth(), totalAmountPaise = 2_00_000_00L)
                .copy(customerName = "Meera")
        bookingRepository.saveBooking(booking)
        bookingRepository.recordPayment(Fixtures.payment(booking.id, amountPaise = 50_000_00L, paidOn = LocalDate.now()))
    }

    @Before
    fun stubOutgoingIntents() {
        Intents.init()
        // Stub EVERYTHING outbound: no chooser/WhatsApp/dialer actually opens on the
        // emulator; the recorded intents are the assertion surface.
        intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun releaseIntents() {
        Intents.release()
    }

    private fun openBookingCard() {
        // Tap the booked DAY CELL (its a11y description carries the booking label) —
        // deterministic, unlike picking the first of several "Meera" text nodes.
        waitForContentDescription("Meera", substring = true).performClick()
        waitForText(string(R.string.booking_card_payments_title))
    }

    @Test
    fun invoicePdf_firesShareChooserIntent() {
        openBookingCard()
        waitForContentDescription(string(R.string.booking_card_action_invoice)).performClick()
        waitForText(string(R.string.booking_card_invoice_pdf)).performClick()

        // PDF rendering is async — wait for the chooser to be recorded. Assertions run
        // on Intents.getIntents() directly: Intents.intended() synchronizes through
        // Espresso's root-view picker, which times out while a Compose ModalBottomSheet
        // window holds focus.
        compose.waitUntil(UI_TIMEOUT_MS) {
            Intents.getIntents().any { it.action == Intent.ACTION_CHOOSER }
        }
        val chooser = Intents.getIntents().first { it.action == Intent.ACTION_CHOOSER }
        val inner =
            @Suppress("DEPRECATION")
            chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertThat(inner).isNotNull()
        assertThat(inner!!.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(inner.type).isEqualTo("application/pdf")
    }

    @Test
    fun whatsAppReminder_carriesPrefilledLocalizedText() {
        openBookingCard()
        waitForContentDescription(string(R.string.booking_card_action_whatsapp)).performClick()

        compose.waitUntil(UI_TIMEOUT_MS) {
            Intents.getIntents().any { it.`package` == "com.whatsapp" }
        }
        val sent = Intents.getIntents().first { it.`package` == "com.whatsapp" }
        assertThat(sent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(sent.type).isEqualTo("text/plain")
        val message = sent.getStringExtra(Intent.EXTRA_TEXT)
        assertThat(message).contains("Meera")
        assertThat(message).contains(AmountFormatter.format(1_50_000_00L)) // the due
    }

    @Test
    fun syncIcon_opensSyncStatusScreen() {
        // The seeded Room writes queued outbox ops; sync is unconfigured in tests, so
        // the app-bar cloud shows the pending state with a badge — tapping opens the
        // Sync-status screen (§4.5).
        waitForContentDescription(string(R.string.common_state_pending)).performClick()
        waitForText(string(R.string.settings_sync_title))
    }

    @Test
    fun menu_opensReports() {
        waitForText(string(R.string.common_nav_menu)).performClick()
        waitForText(string(R.string.menu_section_reports)).performClick()
        waitForText(string(R.string.reports_home_title))
    }
}

@HiltAndroidTest
class CrossFeatureIntentsEnTest : CrossFeatureIntentsTest("en")

@HiltAndroidTest
class CrossFeatureIntentsHiTest : CrossFeatureIntentsTest("hi")
