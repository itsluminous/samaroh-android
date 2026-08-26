package com.itsluminous.samaroh.e2e

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.itsluminous.samaroh.core.i18n.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import java.util.Locale

/**
 * §13 acceptance 1: the in-app language switcher live-swaps the whole UI (per-app
 * locale + activity recreation) — asserted by resolving the SAME keys under the other
 * locale and finding them on screen right after the switch.
 */
abstract class LanguageSwitchTest(
    localeTag: String,
) : LocalizedE2eTest(localeTag) {
    override suspend fun seed() {
        seedOnboardedBusiness()
    }

    private val otherTag: String get() = if (localeTag == "hi") "en" else "hi"

    private fun otherString(id: Int): String {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(otherTag)))
        return app.createConfigurationContext(config).getString(id)
    }

    @Test
    fun languageSwitch_liveSwapsUi() {
        // Menu tab → Settings → Language picker.
        waitForText(string(R.string.common_nav_menu)).performClick()
        waitForText(string(R.string.menu_section_settings)).performClick()
        waitForText(string(R.string.settings_language_title)).performClick()
        waitForText(string(R.string.settings_language_picker_title))

        // Every language renders in its OWN script; pick the other one.
        val otherOwnScriptName =
            string(if (otherTag == "hi") R.string.settings_language_name_hi else R.string.settings_language_name_en)
        waitForText(otherOwnScriptName).performClick()

        // The activity recreates and the SAME screen re-renders localized: picker title
        // and the bottom-bar tab label both resolve under the new locale.
        waitForText(otherString(R.string.settings_language_picker_title))
        waitForText(otherString(R.string.common_nav_menu))
    }
}

@HiltAndroidTest
class LanguageSwitchEnTest : LanguageSwitchTest("en")

@HiltAndroidTest
class LanguageSwitchHiTest : LanguageSwitchTest("hi")
