package com.itsluminous.samaroh.feature.menu.ui.help

import com.itsluminous.samaroh.core.help.GrantedPermissions
import com.itsluminous.samaroh.core.help.HELP_PLATFORM_ANDROID
import com.itsluminous.samaroh.core.help.HelpGuide
import com.itsluminous.samaroh.core.help.HelpScreen
import java.util.Locale

/**
 * Pure view of the guide for the in-app Help screen (ADR-108): what THIS member sees on
 * THIS platform, and live search over it. Android-only entries survive, web-only ones go;
 * a screen or task whose `permission` the member lacks is HIDDEN (not greyed, §3).
 *
 * Search is language-agnostic like Menu search (ADR-075): every whitespace token must be
 * a substring of some text of the entry in EITHER language, so a Hindi user typing an
 * English button name (or vice versa) still finds the task. A screen whose own title or
 * summary matches keeps all its tasks; otherwise only the matching tasks are kept.
 */
object HelpCatalog {
    fun visible(
        guide: HelpGuide,
        granted: GrantedPermissions,
    ): List<HelpScreen> =
        guide
            .forPlatform(HELP_PLATFORM_ANDROID)
            .filter { granted.holds(it.permission) }
            .map { screen -> screen.copy(tasks = screen.tasks.filter { granted.holds(it.permission) }) }
            .filter { it.tasks.isNotEmpty() }

    fun search(
        screens: List<HelpScreen>,
        query: String,
    ): List<HelpScreen> {
        val tokens = normalize(query).split(WHITESPACE).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return screens
        return screens.mapNotNull { screen ->
            val screenTexts = (screen.title.all() + screen.summary.all()).map(::normalize)
            if (tokens.all { token -> screenTexts.any { it.contains(token) } }) return@mapNotNull screen
            val tasks =
                screen.tasks.filter { task ->
                    val texts =
                        (task.title.all() + task.steps.flatMap { it.all() }).map(::normalize)
                    tokens.all { token -> texts.any { it.contains(token) } }
                }
            if (tasks.isEmpty()) null else screen.copy(tasks = tasks)
        }
    }

    private val WHITESPACE = Regex("\\s+")

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT)
}
