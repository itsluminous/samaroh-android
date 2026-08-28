package com.itsluminous.samaroh.applink

/**
 * Destination parsed from an incoming `https://samaroh-web.vercel.app` App Link (ADR-033).
 *
 * Web paths are `/{locale}/section…` with locale `en|hi` — the locale segment is
 * stripped (the app keeps its own language preference) and the section maps onto the
 * four bottom tabs plus the two shell-registered extras (Settings, Reports). Unknown or
 * root paths fall back to the Booking tab, mirroring the web app's home.
 */
sealed interface AppLink {
    /** Booking tab (calendar start destination). Also the unknown/root fallback. */
    data object Booking : AppLink

    /**
     * Expenses tab; [partyId] non-null opens that party's ledger when the party exists
     * locally (unknown ids gracefully stay on the party list).
     */
    data class Expenses(
        val partyId: String? = null,
    ) : AppLink

    /** Inventory tab; [masterlist] switches the stock/masterlist toggle to Masterlist. */
    data class Inventory(
        val masterlist: Boolean = false,
    ) : AppLink

    /** Menu tab; [settings] lands on the Settings screen (any `/menu/settings…` path). */
    data class Menu(
        val settings: Boolean = false,
    ) : AppLink

    /** Reports home (web `/menu/reports`), reached through the Menu tab. */
    data object Reports : AppLink

    companion object {
        private val LOCALES = setOf("en", "hi")

        /**
         * Maps a URI path (e.g. `Uri.path`) to its [AppLink]. Null, blank, malformed and
         * unknown paths all resolve to [Booking]; matching is case-insensitive except
         * for entity ids, which are preserved verbatim.
         */
        fun parse(path: String?): AppLink {
            val segments =
                path
                    .orEmpty()
                    .split('/')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            val afterLocale =
                if (segments.firstOrNull()?.lowercase() in LOCALES) segments.drop(1) else segments
            val section = afterLocale.firstOrNull()?.lowercase() ?: return Booking
            val rest = afterLocale.drop(1)
            return when (section) {
                "booking" -> Booking
                "expenses" -> Expenses(partyId = rest.firstOrNull())
                "inventory" -> Inventory(masterlist = rest.firstOrNull()?.lowercase() == "masterlist")
                "menu" ->
                    when (rest.firstOrNull()?.lowercase()) {
                        "reports" -> Reports
                        "settings" -> Menu(settings = true)
                        else -> Menu()
                    }
                else -> Booking
            }
        }
    }
}
