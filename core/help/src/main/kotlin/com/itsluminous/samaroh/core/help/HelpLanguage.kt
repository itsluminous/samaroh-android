package com.itsluminous.samaroh.core.help

import java.util.Locale

/**
 * The guide's language codes and how the app's locale maps onto them (ADR-108). The guide
 * ships exactly `en` + `hi`; the app's locale is the APP locale (per-app language setting),
 * never the device locale — callers pass the locale of the resources they render with.
 */
object HelpLanguage {
    const val EN = "en"
    const val HI = "hi"

    /** `hi` for any Hindi locale, `en` for everything else (the guide has no other language). */
    fun of(locale: Locale): String = if (locale.language == HI) HI else EN
}
