package com.itsluminous.samaroh.core.help

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The platform this app reports to the guide (`platforms` arrays, `samaroh.app` in JSON-LD). */
const val HELP_PLATFORM_ANDROID = "android"

/**
 * The single-source user guide (`shared/help/guide.json`, schema version 1 — see
 * `shared/help/README.md`). Parsed once from the bundled asset; the in-app Help screen,
 * the assistant context (ADR-108) and the public help site all render THIS structure, so
 * they can never disagree about a button name.
 */
@Serializable
data class HelpGuide(
    val version: Int,
    val languages: List<String>,
    val site: HelpSite,
    val screens: List<HelpScreen>,
) {
    fun screen(id: String?): HelpScreen? = id?.let { screens.firstOrNull { screen -> screen.id == it } }

    /** The public help page of [screenId] in [lang] — the Pages URL the assistant/share use. */
    fun pageUrl(
        lang: String,
        screenId: String,
    ): String = site.pageUrl(lang, screenId)

    /** The public help index of [lang] (fallback for an unknown screen). */
    fun indexUrl(lang: String): String = site.indexUrl(lang)

    /**
     * The guide as this platform shows it: screens, tasks and steps restricted to
     * [platform] (entries without `platforms` apply everywhere). Every returned task keeps
     * at least one step (the shared validator guarantees it; filtered defensively here).
     */
    fun forPlatform(platform: String = HELP_PLATFORM_ANDROID): List<HelpScreen> =
        screens
            .filter { it.appliesTo(platform) }
            .map { screen ->
                screen.copy(
                    tasks =
                        screen.tasks
                            .filter { it.appliesTo(platform) }
                            .map { task -> task.copy(steps = task.steps.filter { it.appliesTo(platform) }) }
                            .filter { it.steps.isNotEmpty() },
                )
            }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): HelpGuide = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class HelpSite(
    val baseUrl: String,
    val pagePattern: String,
    val indexPattern: String,
) {
    fun pageUrl(
        lang: String,
        screenId: String,
    ): String =
        pagePattern
            .replace("{baseUrl}", baseUrl)
            .replace("{lang}", lang)
            .replace("{screenId}", screenId)

    fun indexUrl(lang: String): String =
        indexPattern
            .replace("{baseUrl}", baseUrl)
            .replace("{lang}", lang)
}

/**
 * A text in every guide language (`en`, `hi`, `hi-Latn` — ADR-110). The Hinglish field is
 * nullable only so a guide predating the third language still parses; the shared validator
 * requires all three, and [get] falls back to English for a missing one.
 */
@Serializable
data class LocalizedText(
    val en: String,
    val hi: String,
    @SerialName(HelpLanguage.HI_LATN) val hiLatn: String? = null,
) {
    /** The text in [lang]; an unknown language falls back to English. */
    operator fun get(lang: String): String =
        when (lang) {
            HelpLanguage.HI -> hi
            HelpLanguage.HI_LATN -> hiLatn ?: en
            else -> en
        }

    /** Every language's text, for language-agnostic search (ADR-075 / ADR-108 §4). */
    fun all(): List<String> = listOfNotNull(en, hi, hiLatn)
}

@Serializable
data class HelpScreen(
    val id: String,
    val title: LocalizedText,
    val summary: LocalizedText,
    val deepLink: String,
    val permission: String? = null,
    val platforms: List<String>? = null,
    val tasks: List<HelpTask>,
) {
    fun appliesTo(platform: String): Boolean = platforms == null || platform in platforms
}

@Serializable
data class HelpTask(
    val id: String,
    val title: LocalizedText,
    val deepLink: String,
    val permission: String? = null,
    val platforms: List<String>? = null,
    val steps: List<HelpStep>,
) {
    fun appliesTo(platform: String): Boolean = platforms == null || platform in platforms
}

@Serializable
data class HelpStep(
    val en: String,
    val hi: String,
    @SerialName(HelpLanguage.HI_LATN) val hiLatn: String? = null,
    val keys: List<String>? = null,
    val platforms: List<String>? = null,
) {
    fun appliesTo(platform: String): Boolean = platforms == null || platform in platforms

    operator fun get(lang: String): String =
        when (lang) {
            HelpLanguage.HI -> hi
            HelpLanguage.HI_LATN -> hiLatn ?: en
            else -> en
        }

    /** Every language's text, for language-agnostic search. */
    fun all(): List<String> = listOfNotNull(en, hi, hiLatn)
}

/** `shared/help/routes.json`: deepLink id → per-platform route (null = no equivalent). */
@Serializable
data class HelpRoutes(
    val version: Int,
    val routes: Map<String, HelpRoute>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): HelpRoutes = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class HelpRoute(
    @SerialName("android") val android: String? = null,
    @SerialName("web") val web: String? = null,
)
