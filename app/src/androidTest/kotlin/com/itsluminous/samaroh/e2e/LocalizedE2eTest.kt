package com.itsluminous.samaroh.e2e

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.samaroh.MainActivity
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.repository.InventoryRepository
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.testing.Fixtures
import dagger.hilt.android.testing.HiltAndroidRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject

/** Default wait for reactive UI state (Room flows, nav transitions) to land on screen. */
const val UI_TIMEOUT_MS = 15_000L

/**
 * Base class of the e2e suite (spec §11 W2-B): every acceptance flow runs TWICE — an
 * `En`/`Hi` subclass pair per suite — with the per-app locale set to [localeTag] before
 * the activity launches (§5). Assertions resolve expected strings from the app's own
 * resources under that locale, so a test failing in `hi` but passing in `en` is a real
 * localization bug, never a hardcoded expectation.
 *
 * Per test: Hilt injection, a clean Room database + settings DataStore, optional
 * [seed]ing through the production repositories (Room + outbox, §4.5), then a real
 * [MainActivity] launch.
 */
abstract class LocalizedE2eTest(
    protected val localeTag: String,
) {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose: ComposeTestRule = createEmptyComposeRule()

    @Inject lateinit var database: SamarohDatabase

    @Inject @SettingsDataStore
    lateinit var settings: DataStore<Preferences>

    @Inject lateinit var businessRepository: BusinessRepository

    @Inject lateinit var bookingRepository: BookingRepository

    @Inject lateinit var expensesRepository: ExpensesRepository

    @Inject lateinit var inventoryRepository: InventoryRepository

    protected lateinit var scenario: ActivityScenario<MainActivity>

    protected val testLocale: Locale get() = Locale.forLanguageTag(localeTag)

    /** App context re-configured to [testLocale] — the source of expected strings. */
    protected val localized: Context by lazy {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList(testLocale))
        app.createConfigurationContext(config)
    }

    @Before
    fun setUpHarness() {
        hiltRule.inject()
        runBlocking {
            database.clearAllTables()
            settings.edit { it.clear() }
        }
        applyPerAppLocale()
        runBlocking { seed() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /**
     * Sets the per-app locale BEFORE the activity exists. On API 33+ the framework
     * [android.app.LocaleManager] is authoritative ([AppCompatDelegate] only forwards
     * once an AppCompat activity is attached, so calling it alone pre-launch is a
     * no-op); the AppCompat call keeps the backport path in sync for completeness.
     */
    private fun applyPerAppLocale() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app
                .getSystemService(android.app.LocaleManager::class.java)
                .applicationLocales = LocaleList.forLanguageTags(localeTag)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTag))
        }
    }

    @After
    fun tearDownHarness() {
        if (::scenario.isInitialized) scenario.close()
    }

    /** Per-test data setup through the production repositories; default: fresh install. */
    protected open suspend fun seed() {}

    /** Marks onboarding complete and creates the active business — the usual seed. */
    protected suspend fun seedOnboardedBusiness(name: String = "Sunrise Gardens"): Business {
        settings.edit { it[booleanPreferencesKey("onboarding_complete")] = true }
        val business = Fixtures.business(name = name)
        businessRepository.saveBusiness(business)
        return business
    }

    // ---- localized string helpers ----

    protected fun string(
        @StringRes id: Int,
        vararg args: Any,
    ): String = if (args.isEmpty()) localized.getString(id) else localized.getString(id, *args)

    protected fun plural(
        @PluralsRes id: Int,
        count: Int,
        vararg args: Any,
    ): String = localized.resources.getQuantityString(id, count, *args)

    /** Mirrors `feature:booking` Formatting.formatDate (MEDIUM style, app locale). */
    protected fun formatDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(testLocale))

    /** Mirrors Formatting.formatFullDate — the calendar day-cell a11y prefix. */
    protected fun formatFullDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(testLocale))

    // ---- synchronization helpers ----

    protected fun waitForText(
        text: String,
        substring: Boolean = false,
        timeoutMillis: Long = UI_TIMEOUT_MS,
    ): SemanticsNodeInteraction {
        try {
            compose.waitUntil(timeoutMillis) {
                compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            dumpTree("timed out waiting for text '$text'")
            compose.onNodeWithText(text, substring = substring, useUnmergedTree = true).assertExists("timed out waiting for text '$text'")
        }
        return compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).onFirst()
    }

    protected fun waitForContentDescription(
        description: String,
        substring: Boolean = false,
        timeoutMillis: Long = UI_TIMEOUT_MS,
    ): SemanticsNodeInteraction {
        try {
            compose.waitUntil(timeoutMillis) {
                compose
                    .onAllNodesWithContentDescription(description, substring = substring, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            dumpTree("timed out waiting for content description '$description'")
            compose
                .onNodeWithContentDescription(description, substring = substring, useUnmergedTree = true)
                .assertExists("timed out waiting for content description '$description'")
        }
        return compose.onAllNodesWithContentDescription(description, substring = substring, useUnmergedTree = true).onFirst()
    }

    /** Waits for a type-ahead suggestion (rendered inside the dropdown POPUP window). */
    protected fun waitForSuggestion(
        text: String,
        timeoutMillis: Long = UI_TIMEOUT_MS,
    ): SemanticsNodeInteraction {
        val matcher = hasText(text) and hasAnyAncestor(isPopup())
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onAllNodes(matcher, useUnmergedTree = true).onFirst()
    }

    protected fun waitUntilGone(
        text: String,
        substring: Boolean = false,
        timeoutMillis: Long = UI_TIMEOUT_MS,
    ) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Waits for a node matching text AND content-description-free clickability edge cases. */
    protected fun waitFor(
        matcherDescription: String,
        timeoutMillis: Long = UI_TIMEOUT_MS,
        block: () -> Boolean,
    ) {
        compose.waitUntil(timeoutMillis, block)
    }

    /** Prints the full unmerged semantics tree to logcat + stdout for post-mortems. */
    protected fun dumpTree(reason: String) {
        runCatching {
            val dump = compose.onRoot(useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE)
            android.util.Log.e("SamarohE2e", "$reason\n$dump")
            println("SamarohE2e: $reason\n$dump")
        }
    }

    protected fun textMatcher(
        text: String,
        substring: Boolean = false,
    ) = hasText(text, substring = substring)

    protected fun descriptionMatcher(
        description: String,
        substring: Boolean = false,
    ) = hasContentDescription(description, substring = substring)
}
