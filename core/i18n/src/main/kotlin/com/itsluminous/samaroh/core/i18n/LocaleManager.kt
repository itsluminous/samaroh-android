package com.itsluminous.samaroh.core.i18n

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Per-app locale management (spec §5).
 *
 * Uses [AppCompatDelegate.setApplicationLocales]; persistence across process restarts is
 * handled by AndroidX via the `autoStoreLocales` service metadata declared in the app
 * manifest, and the OS-level per-app language setting (Android 13+) is advertised through
 * `locales_config.xml`. Callers must invoke [setAppLocale] from the main thread after
 * `Activity.onCreate` (an AppCompat activity must be on screen for immediate recreation).
 */
object LocaleManager {
    const val ENGLISH = "en"
    const val HINDI = "hi"

    /**
     * Hinglish — Hindi grammar in Latin script (ADR-110). A BCP-47 tag with a script subtag:
     * its resources live in `values-b+hi+Latn/`, which Android resolves ahead of `values-hi`
     * only when the requested locale carries `Latn` (a plain `hi` request never sees it).
     */
    const val HINGLISH = "hi-Latn"

    /**
     * Locales the app ships, in picker order. Adding a locale = new catalog file in
     * `shared/strings` + entry here + `locales_config.xml` + a name key per picker.
     */
    val supportedLocales: List<String> = listOf(ENGLISH, HINDI, HINGLISH)

    /**
     * Applies [languageTag] (for example `"hi"` or `"hi-Latn"`) as the app-wide locale,
     * recreating visible activities so the change is immediate.
     */
    fun setAppLocale(languageTag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
    }

    /** Currently selected app locale tag, or null when following the system locale. */
    fun currentAppLocale(): String? = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { null }

    /** Clears the per-app override so the app follows the device locale again. */
    fun resetToSystemLocale() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    /**
     * The supported tag [languageTag] denotes, or null. Exact match on language + script:
     * `"hi-Latn".startsWith("hi")` is true, so a prefix test would tick the Hindi row for a
     * Hinglish selection — the pitfall `shared/docs/hinglish.md` warns about. Region subtags
     * the framework may append (`en-US`, `hi-IN`) are ignored.
     */
    fun supportedTagOf(languageTag: String?): String? {
        if (languageTag.isNullOrEmpty()) return null
        val locale = Locale.forLanguageTag(languageTag.split(',').first())
        return supportedLocales.firstOrNull { tag ->
            val supported = Locale.forLanguageTag(tag)
            supported.language == locale.language && supported.script == locale.script
        }
    }

    /** Settings-picker display name of a supported tag, in the language's OWN script (locale-invariant keys). */
    @StringRes
    fun settingsNameRes(tag: String): Int =
        when (tag) {
            HINDI -> R.string.settings_language_name_hi
            HINGLISH -> R.string.settings_language_name_hi_latn
            else -> R.string.settings_language_name_en
        }

    /** Onboarding-picker display name of a supported tag (`common.language.*`, same values as the Settings keys). */
    @StringRes
    fun commonNameRes(tag: String): Int =
        when (tag) {
            HINDI -> R.string.common_language_hi
            HINGLISH -> R.string.common_language_hi_latn
            else -> R.string.common_language_en
        }
}
