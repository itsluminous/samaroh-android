package com.itsluminous.samaroh.core.help

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.samaroh.core.data.navigation.HelpDeepLinkIds
import com.itsluminous.samaroh.core.data.navigation.HelpScreenIds
import org.junit.Test
import java.io.File

/**
 * Drift guards between the shared guide and this repo (ADR-108): the Kotlin id constants
 * mirror `guide.json` / `routes.json`, every `keys` reference in a step is a real catalog
 * key of BOTH locales (the Help screen quotes those labels verbatim), and every screen
 * survives the Android platform filter with at least one task.
 */
class HelpGuideContractTest {
    private val guide = SharedHelpFiles.guide()
    private val routes = SharedHelpFiles.routes()

    @Test
    fun `guide is schema version 1 with en and hi`() {
        assertThat(guide.version).isEqualTo(1)
        assertThat(guide.languages).containsAtLeast(HelpLanguage.EN, HelpLanguage.HI)
        assertThat(guide.site.baseUrl).startsWith("https://")
    }

    @Test
    fun `HelpScreenIds mirrors the guide screen ids exactly`() {
        assertThat(HelpScreenIds.all).containsExactlyElementsIn(guide.screens.map { it.id })
        assertThat(HelpScreenIds.HELP).isNotIn(guide.screens.map { it.id })
    }

    @Test
    fun `HelpDeepLinkIds mirrors routes json exactly`() {
        assertThat(HelpDeepLinkIds.all).containsExactlyElementsIn(routes.routes.keys)
        // Every id a feature claims is a real route, and the per-feature sets are disjoint.
        val claimed = listOf(HelpDeepLinkIds.booking, HelpDeepLinkIds.expenses, HelpDeepLinkIds.inventory, HelpDeepLinkIds.menu)
        claimed.forEach { set -> assertThat(routes.routes.keys).containsAtLeastElementsIn(set) }
        assertThat(claimed.sumOf { it.size }).isEqualTo(claimed.flatten().toSet().size)
    }

    @Test
    fun `every deepLink used by the guide has an android route`() {
        val used = guide.screens.flatMap { screen -> listOf(screen.deepLink) + screen.tasks.map { it.deepLink } }.toSet()
        for (id in used) {
            val route = routes.routes[id]
            assertWithMessage("deepLink '$id' is missing from routes.json").that(route).isNotNull()
            assertWithMessage("deepLink '$id' has no android route").that(route!!.android).isNotNull()
        }
    }

    @Test
    fun `every step key is a catalog key present in both locales`() {
        val en = SharedHelpFiles.catalogKeys("en")
        val hi = SharedHelpFiles.catalogKeys("hi")
        assertThat(en).isNotEmpty()
        val missing = mutableListOf<String>()
        guide.screens.forEach { screen ->
            screen.tasks.forEach { task ->
                task.steps.forEach { step ->
                    step.keys.orEmpty().forEach { key ->
                        if (key !in en) missing.add("${screen.id}/${task.id}: '$key' not in en catalog")
                        if (key !in hi) missing.add("${screen.id}/${task.id}: '$key' not in hi catalog")
                    }
                }
            }
        }
        assertWithMessage("guide steps reference unknown catalog keys").that(missing).isEmpty()
    }

    @Test
    fun `android view keeps every screen with at least one task and every task with a step`() {
        val screens = guide.forPlatform(HELP_PLATFORM_ANDROID)
        assertThat(screens.map { it.id }).containsExactlyElementsIn(guide.screens.map { it.id })
        screens.forEach { screen ->
            assertWithMessage("screen ${screen.id} has no android task").that(screen.tasks).isNotEmpty()
            screen.tasks.forEach { task ->
                assertWithMessage("task ${screen.id}/${task.id} has no android step").that(task.steps).isNotEmpty()
            }
        }
        // A web-only step must be gone: sign_in/join_business is android-only and survives,
        // while no surviving step may name 'web' exclusively.
        screens.flatMap { it.tasks }.flatMap { it.steps }.forEach { step ->
            assertThat(step.platforms == null || HELP_PLATFORM_ANDROID in step.platforms).isTrue()
        }
    }

    @Test
    fun `bundled asset copy exists only after generateHelpAssets ran - shared file is what tests read`() {
        assertThat(File(SharedHelpFiles.helpDir, "guide.json").exists()).isTrue()
        assertThat(File(SharedHelpFiles.helpDir, "routes.json").exists()).isTrue()
    }

    @Test
    fun `page and index urls follow the site patterns`() {
        assertThat(guide.pageUrl("hi", "booking_card")).isEqualTo("${guide.site.baseUrl}hi/booking_card.html")
        assertThat(guide.indexUrl("en")).isEqualTo("${guide.site.baseUrl}en/index.html")
    }
}
