package com.itsluminous.samaroh.e2e

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.i18n.LocaleManager
import com.itsluminous.samaroh.core.i18n.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import java.util.Locale

/**
 * §13 acceptance 1: the in-app language switcher live-swaps the whole UI (per-app
 * locale + activity recreation) — asserted by resolving the SAME keys under the other
 * locale and finding them on screen right after the switch. The three runs walk the
 * cycle en → hi → hi-Latn → en, so every language is both a start and a target, and the
 * Hinglish leg proves `values-b+hi+Latn` wins over `values-hi` (ADR-110).
 */
abstract class LanguageSwitchTest(
    localeTag: String,
) : LocalizedE2eTest(localeTag) {
    override suspend fun seed() {
        seedOnboardedBusiness()
    }

    /** The next language in picker order (wrapping): en → hi → hi-Latn → en. */
    private val otherTag: String
        get() {
            val tags = LocaleManager.supportedLocales
            return tags[(tags.indexOf(localeTag) + 1) % tags.size]
        }

    private fun otherString(id: Int): String {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(otherTag)))
        return app.createConfigurationContext(config).getString(id)
    }

    @Test
    fun languageSwitch_liveSwapsUi() {
        // Title-bar Menu kebab (ADR-087) → Settings → Language picker.
        waitForContentDescription(string(R.string.common_nav_menu)).tap()
        waitForText(string(R.string.menu_section_settings)).tap()
        waitForText(string(R.string.settings_language_title)).tap()
        waitForText(string(R.string.settings_language_picker_title))

        // Every language renders in its OWN script; pick the other one.
        waitForText(string(LocaleManager.settingsNameRes(otherTag))).tap()

        // The activity recreates and the SAME screen re-renders localized: picker title
        // and the bottom-bar tab label both resolve under the new locale — and differ from
        // this run's (hi vs hi-Latn share a language; only the script-qualified resources
        // tell them apart).
        val otherTitle = otherString(R.string.settings_language_picker_title)
        val otherTab = otherString(R.string.common_nav_expenses)
        assertThat(otherTitle).isNotEqualTo(string(R.string.settings_language_picker_title))
        assertThat(otherTab).isNotEqualTo(string(R.string.common_nav_expenses))
        waitForText(otherTitle)
        waitForText(otherTab)
    }
}

@HiltAndroidTest
class LanguageSwitchEnTest : LanguageSwitchTest("en")

@HiltAndroidTest
class LanguageSwitchHiTest : LanguageSwitchTest("hi")

@HiltAndroidTest
class LanguageSwitchHiLatnTest : LanguageSwitchTest("hi-Latn")
