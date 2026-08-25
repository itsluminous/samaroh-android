package com.itsluminous.samaroh.core.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

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
    /** Locales shipped in v1. Adding a locale = new catalog file in `shared/strings` + entry here + `locales_config.xml`. */
    val supportedLocales: List<String> = listOf("en", "hi")

    /**
     * Applies [languageTag] (for example `"hi"`) as the app-wide locale, recreating visible
     * activities so the change is immediate.
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
}
