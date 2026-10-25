package com.itsluminous.samaroh.core.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Resource resolution of the Hinglish qualifier (ADR-110), on the app's minSdk (26 — the
 * AppCompat per-app-locale backport path) and on a per-app-locale OS (35): a request for
 * `hi-Latn` must land on `values-b+hi+Latn/`, never on `values-hi/` (same language, other
 * script) nor on `values/`; a plain `hi` request must never see the Latin-script strings.
 * Robolectric resolves against the real compiled resource table, so this is the framework's
 * BCP-47 matching, not a re-implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class HinglishResourceResolutionTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private fun under(tag: String): Context {
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return app.createConfigurationContext(config)
    }

    @Test
    fun `hi-Latn resolves values-b+hi+Latn over values-hi and values`() {
        val hinglish = under("hi-Latn")
        assertThat(hinglish.getString(R.string.common_nav_expenses)).isEqualTo("Kharche")
        assertThat(hinglish.getString(R.string.common_action_save)).isNotEqualTo(under("en").getString(R.string.common_action_save))
        assertThat(hinglish.getString(R.string.common_action_save)).doesNotContainMatch("[\\u0900-\\u097F]")
        // The Latin-script directory wins even when the framework appends a region.
        assertThat(under("hi-Latn-IN").getString(R.string.common_nav_expenses)).isEqualTo("Kharche")
    }

    @Test
    fun `hi still resolves values-hi, en resolves values`() {
        assertThat(under("hi").getString(R.string.common_nav_expenses)).isEqualTo("खर्च")
        assertThat(under("hi-IN").getString(R.string.common_nav_expenses)).isEqualTo("खर्च")
        assertThat(under("en").getString(R.string.common_nav_expenses)).isEqualTo("Expenses")
        assertThat(under("en-IN").getString(R.string.common_nav_expenses)).isEqualTo("Expenses")
    }

    @Test
    fun `non-translatable keys fall back to values under every locale`() {
        val en = under("en").getString(R.string.menu_about_source_code_url)
        assertThat(en).isNotEmpty()
        assertThat(under("hi").getString(R.string.menu_about_source_code_url)).isEqualTo(en)
        assertThat(under("hi-Latn").getString(R.string.menu_about_source_code_url)).isEqualTo(en)
    }

    @Test
    fun `own-script language names are identical under every locale`() {
        for (tag in LocaleManager.supportedLocales) {
            val ctx = under(tag)
            assertThat(ctx.getString(R.string.settings_language_name_hi_latn)).isEqualTo("Hinglish")
            assertThat(ctx.getString(R.string.settings_language_name_hi)).isEqualTo("हिन्दी")
            assertThat(ctx.getString(R.string.settings_language_name_en)).isEqualTo("English")
        }
    }
}

/** The same resolution driven by the test configuration's qualifier string, as a device's locale would. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], qualifiers = "b+hi+Latn")
class HinglishQualifierTest {
    @Test
    fun `the b+hi+Latn configuration renders Hinglish`() {
        val app: Context = ApplicationProvider.getApplicationContext()
        assertThat(
            app.resources.configuration.locales[0]
                .toLanguageTag(),
        ).isEqualTo("hi-Latn")
        assertThat(app.getString(R.string.common_nav_expenses)).isEqualTo("Kharche")
        assertThat(app.getString(R.string.common_nav_booking)).doesNotContainMatch("[\\u0900-\\u097F]")
    }
}
