package com.itsluminous.samaroh.feature.menu.ui.search

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** A [MenuSearchEntry] with its display texts and search haystack resolved. */
data class ResolvedMenuSearchEntry(
    val entry: MenuSearchEntry,
    /** Title in the CURRENT app locale — what the result row shows. */
    val title: String,
    /** Context/breadcrumb line in the current app locale, or null for top-level rows. */
    val context: String?,
    /** Normalized searchable texts across every indexed locale. */
    val haystack: List<String>,
)

/**
 * Menu search resolution + filtering (ADR-075). The index resolves ONCE per screen:
 * every entry's title, context and keywords in BOTH catalog locales (en + hi), so the
 * query matches whichever language the user types in, regardless of the app locale.
 * Matching is normalized-substring: every whitespace-separated query token must be a
 * substring of some indexed text.
 */
object MenuSearch {
    /** The catalog locales the index resolves in (parity-tested in `core:i18n`). */
    private val INDEX_LOCALES = listOf("en", "hi")

    fun resolve(
        context: Context,
        entries: List<MenuSearchEntry> = MenuSearchIndex.entries,
    ): List<ResolvedMenuSearchEntry> {
        val localized = INDEX_LOCALES.map { tag -> context.forLocale(tag) }
        return entries.map { entry ->
            val haystack =
                buildList {
                    localized.forEach { localeContext ->
                        add(localeContext.getString(entry.titleRes))
                        entry.contextRes?.let { add(localeContext.getString(it)) }
                        entry.keywordRes.forEach { add(localeContext.getString(it)) }
                    }
                }.map(::normalize)
            ResolvedMenuSearchEntry(
                entry = entry,
                title = context.getString(entry.titleRes),
                context = entry.contextRes?.let(context::getString),
                haystack = haystack,
            )
        }
    }

    /**
     * Live filter: blank query → empty (the screen shows the normal menu instead);
     * otherwise every query token must substring-match some haystack text, and
     * owner/session gating drops destinations the user cannot reach.
     */
    fun filter(
        entries: List<ResolvedMenuSearchEntry>,
        query: String,
        isOwner: Boolean,
        isSignedIn: Boolean,
    ): List<ResolvedMenuSearchEntry> {
        val tokens = normalize(query).split(WHITESPACE).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return emptyList()
        return entries.filter { resolved ->
            (!resolved.entry.ownerOnly || isOwner) &&
                (!resolved.entry.requiresSignedIn || isSignedIn) &&
                tokens.all { token -> resolved.haystack.any { it.contains(token) } }
        }
    }

    private val WHITESPACE = Regex("\\s+")

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT)

    private fun Context.forLocale(tag: String): Context =
        createConfigurationContext(
            Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
        )
}
