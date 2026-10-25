package com.itsluminous.samaroh.core.help

import java.util.Locale

/**
 * The guide's language codes and how the app's locale maps onto them (ADR-108, ADR-110). The
 * guide ships `en`, `hi` and `hi-Latn` (Hinglish); the app's locale is the APP locale (per-app
 * language setting), never the device locale — callers pass the locale of the resources they
 * render with. The codes double as the help site's path segment (`/help/<code>/…`).
 */
object HelpLanguage {
    const val EN = "en"
    const val HI = "hi"

    /** Hinglish — BCP-47 Hindi in Latin script; the guide/site key is the tag itself. */
    const val HI_LATN = "hi-Latn"

    val ALL: List<String> = listOf(EN, HI, HI_LATN)

    /**
     * `hi-Latn` for Latin-script Hindi, `hi` for any other Hindi locale, `en` for everything
     * else (the guide has no other language). Script, not region, decides: `hi-IN` is `hi`.
     */
    fun of(locale: Locale): String =
        when {
            locale.language != HI -> EN
            locale.script == "Latn" -> HI_LATN
            else -> HI
        }
}
