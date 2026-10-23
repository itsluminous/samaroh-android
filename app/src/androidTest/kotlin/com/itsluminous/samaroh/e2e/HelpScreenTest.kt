package com.itsluminous.samaroh.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.navigation.HelpScreenIds
import com.itsluminous.samaroh.core.help.HelpGuideSource
import com.itsluminous.samaroh.core.help.HelpScreen
import com.itsluminous.samaroh.core.help.HelpTask
import com.itsluminous.samaroh.core.i18n.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import javax.inject.Inject

/**
 * ADR-108 in-app Help on the real shell: Menu → Help lists the guide in the run's language,
 * search narrows it, "Show me" lands on the task's screen (a tab's inner form, a Menu
 * subscreen), and the app-bar `?` opens Help at the current screen's section.
 */
abstract class HelpScreenTest(
    localeTag: String,
) : LocalizedE2eTest(localeTag) {
    @Inject lateinit var guideSource: HelpGuideSource

    override suspend fun seed() {
        seedOnboardedBusiness()
    }

    private fun screen(id: String): HelpScreen = guideSource.guide.forPlatform().first { it.id == id }

    private fun task(
        screenId: String,
        taskId: String,
    ): HelpTask = screen(screenId).tasks.first { it.id == taskId }

    private fun searchHelp(query: String) {
        compose
            .onNode(hasSetTextAction() and hasText(string(R.string.menu_help_search_placeholder)))
            .performTextInput(query)
    }

    private fun openHelpFromMenu() {
        waitForContentDescription(string(R.string.common_nav_menu)).performClick()
        waitForText(string(R.string.common_menu_help)).performClick()
        waitForText(string(R.string.menu_help_search_placeholder))
    }

    @Test
    fun menuHelp_listsGuide_searchFinds_showMeOpensBookingForm() {
        waitForText(string(R.string.booking_summary_this_month))
        openHelpFromMenu()
        // The first guide screen's title renders in the run's language.
        waitForText(screen(HelpScreenIds.APP_SHELL).title[localeTag])

        // Search by the add-booking task title → that task row appears; tap it → steps + Show me.
        val addBooking = task(HelpScreenIds.BOOKING_CALENDAR, "add_booking")
        searchHelp(addBooking.title[localeTag])
        waitForText(addBooking.title[localeTag]).performClick()
        waitForText(addBooking.steps.first()[localeTag], substring = true)
        compose.onAllNodesWithText(string(R.string.menu_help_show_me), useUnmergedTree = true).onFirst().performClick()

        // Show me (booking/form): the Booking tab's add form.
        waitForText(string(R.string.booking_form_customer_name))
    }

    @Test
    fun showMe_menuTarget_landsOnLanguagePicker() {
        waitForText(string(R.string.booking_summary_this_month))
        openHelpFromMenu()
        val changeLanguage = task(HelpScreenIds.SETTINGS, "change_language")
        searchHelp(changeLanguage.title[localeTag])
        waitForText(changeLanguage.title[localeTag]).performClick()
        compose.onAllNodesWithText(string(R.string.menu_help_show_me), useUnmergedTree = true).onFirst().performClick()
        // menu/settings/language → the language picker, over the Menu home.
        waitForText(string(R.string.settings_language_picker_title))
    }

    @Test
    fun questionMark_opensHelpAtCurrentScreen_andIsHiddenOnHelp() {
        waitForText(string(R.string.booking_summary_this_month))
        waitForContentDescription(string(R.string.menu_help_for_this_screen)).performClick()
        // Help opened pre-scrolled: the calendar section header is on screen.
        waitForText(screen(HelpScreenIds.BOOKING_CALENDAR).title[localeTag]).assertIsDisplayed()
        // The `?` is gone while Help is open (no second Help over Help).
        assertThat(
            compose
                .onAllNodesWithContentDescription(string(R.string.menu_help_for_this_screen), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        ).isTrue()
        compose.onAllNodesWithText(string(R.string.common_menu_help), useUnmergedTree = true).onFirst().assertIsDisplayed()
    }
}

@HiltAndroidTest
class HelpScreenEnTest : HelpScreenTest("en")

@HiltAndroidTest
class HelpScreenHiTest : HelpScreenTest("hi")
