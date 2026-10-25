package com.itsluminous.samaroh.core.i18n

import java.util.Locale

/**
 * The locale `java.time` / `NumberFormat` formatters use for a given APP locale (ADR-110).
 *
 * Hinglish (`hi-Latn`) has no formatting data of its own: ICU carries `hi_Latn` only since
 * CLDR 38 (Android 12), and there it simply inherits `en_IN`; on the app's minSdk (26–30) the
 * script subtag is dropped and the formatter falls back to `hi` — Devanagari month names
 * (`25 अक्तू॰ 2026`) inside an otherwise Latin-script UI. Pinning Hinglish to `en-IN`
 * (Latin digits, English month/day names, Indian day-month order) gives the same output on
 * every API level and matches how Hinglish speakers write dates. Every other locale formats
 * as itself, so `en`/`hi` are untouched.
 *
 * Every formatting site resolves its locale through [of] / [default] — never reads
 * `LocalConfiguration.current.locales[0]` or `Locale.getDefault()` directly for formatting.
 */
object FormatLocale {
    /** The formatting locale Hinglish borrows. */
    val HINGLISH_FORMAT: Locale = Locale("en", "IN")

    /** True for any Latin-script Hindi locale (`hi-Latn`, `hi-Latn-IN`). */
    fun isHinglish(locale: Locale): Boolean = locale.language == LocaleManager.HINDI && locale.script == "Latn"

    /** The locale to format dates/numbers with when the UI renders in [appLocale]. */
    fun of(appLocale: Locale?): Locale =
        when {
            appLocale == null -> of(Locale.getDefault())
            isHinglish(appLocale) -> HINGLISH_FORMAT
            else -> appLocale
        }

    /** [of] applied to the process default locale — for non-Compose call sites (receivers, dialogs). */
    fun default(): Locale = of(Locale.getDefault())
}
