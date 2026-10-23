package com.itsluminous.samaroh.e2e

import android.app.assist.AssistContent
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.navigation.HelpScreenIds
import com.itsluminous.samaroh.core.help.AssistContextBuilder
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.testing.Fixtures
import dagger.hilt.android.testing.HiltAndroidTest
import org.json.JSONObject
import org.junit.Test

/**
 * ADR-108 assistant context, on the real shell (the emulator has no Gemini, so the test
 * plays the assistant): `onProvideAssistContent` is asked on several screens — calendar,
 * the open booking card, the record-payment sheet, Expenses, Settings — and must hand back
 * the help page of THAT screen in the run's language plus JSON-LD naming the screen, with
 * none of the seeded customer data in it.
 */
abstract class AssistContentTest(
    localeTag: String,
) : LocalizedE2eTest(localeTag) {
    private val customer = "Meera"

    override suspend fun seed() {
        seedOnboardedBusiness()
        bookingRepository.saveBooking(
            Fixtures.booking(startDate = futureDateInCurrentMonth(), totalAmountPaise = 2_00_000_00L).copy(customerName = customer),
        )
    }

    /** What the assistant would receive right now. */
    private fun provideAssistContent(): AssistContent {
        val content = AssistContent()
        scenario.onActivity { activity -> activity.onProvideAssistContent(content) }
        return content
    }

    private fun assertScreen(
        content: AssistContent,
        screenId: String,
    ) {
        assertThat(content.webUri.toString()).endsWith("/help/$localeTag/$screenId.html")
        val json = JSONObject(content.structuredData)
        assertThat(json.getString("@type")).isEqualTo("WebPage")
        assertThat(json.getString("inLanguage")).isEqualTo(localeTag)
        assertThat(json.getString("url")).isEqualTo(content.webUri.toString())
        val samaroh = json.getJSONObject("samaroh")
        assertThat(samaroh.getString("screenId")).isEqualTo(screenId)
        assertThat(samaroh.getString("language")).isEqualTo(localeTag)
        assertThat(samaroh.getString("app")).isEqualTo("android")
        assertThat(samaroh.getString("guideIndexUrl")).endsWith("/help/$localeTag/index.html")
        assertThat(samaroh.getJSONArray("tasks").length()).isGreaterThan(0)
        assertThat(content.structuredData.length).isAtMost(AssistContextBuilder.MAX_JSON_LD_CHARS)
        // Nothing from the user's data (customer, business, amounts) ever rides along.
        assertThat(content.structuredData).doesNotContain(customer)
        assertThat(content.structuredData).doesNotContain("Sunrise Gardens")
        assertThat(content.structuredData).doesNotContain("2,00,000")
    }

    @Test
    fun calendar_card_and_recordPayment_sheets_reportTheirOwnPages() {
        waitForText(string(R.string.booking_summary_this_month))
        assertScreen(provideAssistContent(), HelpScreenIds.BOOKING_CALENDAR)

        // Open the booking card (day sheet → row).
        waitForContentDescription(customer, substring = true).performClick()
        waitForText(string(R.string.booking_calendar_add_new_event))
        compose
            .onNode(hasAnyAncestor(hasTestTag("day_sheet")) and hasText(customer, substring = true), useUnmergedTree = true)
            .performClick()
        waitForText(string(R.string.booking_card_payments_title))
        assertScreen(provideAssistContent(), HelpScreenIds.BOOKING_CARD)

        // Record payment sheet over the card.
        waitForContentDescription(string(R.string.booking_card_action_record_payment)).performClick()
        waitForText(string(R.string.booking_payment_amount), substring = true)
        assertScreen(provideAssistContent(), HelpScreenIds.RECORD_PAYMENT)
    }

    @Test
    fun expensesTab_and_settings_reportTheirPages() {
        waitForText(string(R.string.booking_summary_this_month))
        waitForText(string(R.string.common_nav_expenses)).performClick()
        waitForText(string(R.string.expenses_home_you_gave))
        assertScreen(provideAssistContent(), HelpScreenIds.EXPENSES_HOME)

        waitForContentDescription(string(R.string.common_nav_menu)).performClick()
        waitForText(string(R.string.menu_section_settings))
        assertScreen(provideAssistContent(), HelpScreenIds.MENU)
        waitForText(string(R.string.menu_section_settings)).performClick()
        waitForText(string(R.string.settings_language_title))
        assertScreen(provideAssistContent(), HelpScreenIds.SETTINGS)
    }
}

@HiltAndroidTest
class AssistContentEnTest : AssistContentTest("en")

@HiltAndroidTest
class AssistContentHiTest : AssistContentTest("hi")
