package com.itsluminous.samaroh.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.itsluminous.samaroh.core.i18n.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test

/**
 * §13 acceptance 1+3: first launch shows the language picker; onboarding creates a
 * business fully offline and lands on the Booking calendar with the business name in
 * the app bar.
 */
abstract class OnboardingFlowTest(
    localeTag: String,
) : LocalizedE2eTest(localeTag) {
    @Test
    fun onboarding_createBusiness_landsOnCalendar() {
        // Step 1 — language picker is the FIRST screen; pick the run's language
        // (rendered in its own script regardless of current locale).
        waitForText(string(R.string.onboarding_language_title))
        val ownScriptName =
            string(if (localeTag == "hi") R.string.common_language_hi else R.string.common_language_en)
        compose.onNode(hasText(ownScriptName)).performClick()
        compose.onNode(hasText(string(R.string.onboarding_action_continue))).performClick()

        // Step 2 — welcome carousel, skippable.
        waitForText(string(R.string.onboarding_welcome_skip)).performClick()

        // Step 3 — sign-in; offline-first path is never a dead end.
        waitForText(string(R.string.onboarding_sign_in_continue_offline)).performClick()

        // Step 4 — fork: create a new business.
        waitForText(string(R.string.onboarding_fork_create)).performClick()

        // Step 5 — create-business form (name* + owner name*).
        waitForText(string(R.string.onboarding_create_title))
        compose
            .onNode(hasSetTextAction() and hasText(string(R.string.onboarding_create_name_label)))
            .performTextInput("Sunrise Gardens")
        compose
            .onNode(hasSetTextAction() and hasText(string(R.string.onboarding_create_owner_label)))
            .performTextInput("Asha")
        compose.onNode(hasText(string(R.string.onboarding_create_submit))).performClick()

        // Step 6 — Google linking is optional with a prominent "later".
        waitForText(string(R.string.onboarding_link_google_later)).performClick()

        // Step 7 — Booking tab: month summary card + the business name in the app bar.
        waitForText(string(R.string.booking_summary_this_month))
        waitForText("Sunrise Gardens")
    }
}

@HiltAndroidTest
class OnboardingFlowEnTest : OnboardingFlowTest("en")

@HiltAndroidTest
class OnboardingFlowHiTest : OnboardingFlowTest("hi")
