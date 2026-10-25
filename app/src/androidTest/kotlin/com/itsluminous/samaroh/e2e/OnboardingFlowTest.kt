package com.itsluminous.samaroh.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import com.itsluminous.samaroh.core.i18n.LocaleManager
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
        compose.onNode(hasText(string(LocaleManager.commonNameRes(localeTag)))).tap()
        compose.onNode(hasText(string(R.string.onboarding_action_continue))).tap()

        // Step 2 — welcome carousel, skippable.
        waitForText(string(R.string.onboarding_welcome_skip)).tap()

        // Step 3 — sign-in; offline-first path is never a dead end.
        waitForText(string(R.string.onboarding_sign_in_continue_offline)).tap()

        // Step 4 — fork: create a new business.
        waitForText(string(R.string.onboarding_fork_create)).tap()

        // Step 5 — create-business form (name* + owner name*).
        waitForText(string(R.string.onboarding_create_title))
        compose
            .onNode(hasSetTextAction() and hasText(string(R.string.onboarding_create_name_label)))
            .typeText("Sunrise Gardens")
        compose
            .onNode(hasSetTextAction() and hasText(string(R.string.onboarding_create_owner_label)))
            .typeText("Asha")
        compose.onNode(hasText(string(R.string.onboarding_create_submit))).tap()

        // Step 6 — Google linking is optional with a prominent "later".
        waitForText(string(R.string.onboarding_link_google_later)).tap()

        // Step 7 — Booking tab: month summary card + the business name in the app bar.
        waitForText(string(R.string.booking_summary_this_month))
        waitForText("Sunrise Gardens")
    }
}

@HiltAndroidTest
class OnboardingFlowEnTest : OnboardingFlowTest("en")

@HiltAndroidTest
class OnboardingFlowHiTest : OnboardingFlowTest("hi")

/** Hinglish (ADR-110): the third picker row, and every later screen in Latin-script Hindi. */
@HiltAndroidTest
class OnboardingFlowHiLatnTest : OnboardingFlowTest("hi-Latn")
