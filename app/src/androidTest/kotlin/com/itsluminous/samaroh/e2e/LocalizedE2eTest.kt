package com.itsluminous.samaroh.e2e

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.LocaleList
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.Choreographer
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppLocalesMetadataHolderService
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.itsluminous.samaroh.MainActivity
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.EventTypeRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.repository.InventoryRepository
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.i18n.FormatLocale
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Default wait for reactive UI state (Room flows, nav transitions) to land on screen. */
const val UI_TIMEOUT_MS = 15_000L

/** Upper bound for the framework to apply a per-app locale to the test process. */
private const val LOCALE_APPLY_TIMEOUT_MS = 5_000L
private const val LOCALE_POLL_MS = 25L

/** Poll interval while waiting for the system to post a notification. */
private const val NOTIFICATION_POLL_MS = 50L

/** Upper bound for the soft keyboard to be laid out after a text field took focus. */
private const val IME_SHOW_TIMEOUT_MS = 2_000L

/** Upper bound for the main thread to render one frame (CI emulators run ~2 fps under load). */
private const val FRAME_TIMEOUT_MS = 5_000L

/**
 * Base class of the e2e suite (spec §11 W2-B): every acceptance flow runs per locale — an
 * `En`/`Hi` subclass pair per suite, plus a `HiLatn` (Hinglish, ADR-110) variant for the
 * language-facing flows — with the per-app locale set to [localeTag] before the activity
 * launches (§5). Assertions resolve expected strings from the app's own resources under that
 * locale, so a test failing in `hi` but passing in `en` is a real localization bug, never a
 * hardcoded expectation.
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

    @Inject lateinit var eventTypeRepository: EventTypeRepository

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
        grantNotificationPermission()
        clearStaleNotifications()
        runBlocking {
            database.clearAllTables()
            settings.edit { it.clear() }
        }
        disarmAppCompatLocaleSync()
        applyPerAppLocale()
        awaitProcessLocale()
        runBlocking { seed() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /**
     * Makes the per-app locale survive the FIRST AppCompat activity of the process.
     *
     * With `autoStoreLocales` opted in (manifest), the first `AppCompatActivity` of a process
     * on API 33+ runs AppCompat's one-time "androidx → framework locale sync"
     * (`AppCompatDelegate.syncLocalesToFramework`) on a worker thread from
     * `attachBaseContext`. That job treats the ENABLED state of
     * [AppLocalesMetadataHolderService] as its "already synced" marker; the manifest declares
     * the service `enabled="false"`, and the suite reinstalls the APK every run, so the
     * marker is unset for the first activity of every run. The job then asks
     * `AppCompatDelegate.getApplicationLocales()`, which on 33+ resolves the framework
     * `LocaleManager` through the ACTIVE delegates — and the delegate is only registered in
     * `onCreate`, AFTER the job was queued. Losing that race, it reads "no app locale", finds
     * nothing in AppCompat's own storage either, and pushes an EMPTY locale list to the
     * framework: the per-app locale set by [applyPerAppLocale] is wiped, the process flips
     * back to the system locale and the freshly-resumed activity is recreated in English
     * (observed: `ReminderDeepLinkHiTest.warm_…` failing whenever a `Hi` class is the first
     * to launch an activity; harmless for `En`, so the full suite masked it behind the
     * alphabetical `En` classes). Setting the marker ourselves — exactly what the sync does
     * on success — makes the job a no-op, so the outcome no longer depends on class order or
     * scheduler luck. Production is unaffected (there the storage and the framework agree).
     */
    private fun disarmAppCompatLocaleSync() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.packageManager.setComponentEnabledSetting(
            ComponentName(app, AppLocalesMetadataHolderService::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * Blocks until the framework has pushed the requested per-app locale into THIS process's
     * configuration (`LocaleManager.setApplicationLocales` is an IPC whose effect lands
     * asynchronously), so the activity launched next is created under [testLocale] instead of
     * starting in the previous locale and being recreated mid-test. Fails loudly on timeout —
     * a vague "could not find text" later would hide the real cause.
     */
    private fun awaitProcessLocale() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val deadline = SystemClock.uptimeMillis() + LOCALE_APPLY_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val locales = app.resources.configuration.locales
            // Language AND script: a stale `hi` must not pass for the `hi-Latn` run (or vice versa).
            if (!locales.isEmpty && locales[0].language == testLocale.language && locales[0].script == testLocale.script) return
            Thread.sleep(LOCALE_POLL_MS)
        }
        error("per-app locale '$localeTag' did not reach the process configuration within ${LOCALE_APPLY_TIMEOUT_MS}ms")
    }

    /**
     * Pre-grants POST_NOTIFICATIONS: the suite reinstalls the app every run, so the
     * contextual first-form-open request (ADR-044) would otherwise fire the SYSTEM
     * permission dialog mid-test — the activity pauses behind it and the compose tree
     * is unreachable ("No compose hierarchies found") until the dialog is dismissed,
     * which no test does. Granting up front keeps the production request path a
     * silent no-op (already granted).
     */
    private fun grantNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val app = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Drops every notification a previous test left in the shade. A test that failed
     * between posting a reminder and tapping it leaves that reminder behind, and its
     * content intent points at a booking the next test's `clearAllTables()` has already
     * erased — the next `ReminderDeepLink*` test must never be able to pick it up.
     */
    private fun clearStaleNotifications() {
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSystemService(NotificationManager::class.java)
            .cancelAll()
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
        // Mirror production business creation (ADR-032): presets seed at creation.
        eventTypeRepository.seedDefaults(business.id)
        return business
    }

    /**
     * A current-month date that is TODAY OR LATER: bookings seeded on it never trigger
     * the async "confirm payment?" reminder card (bookings ENDED before today do),
     * whose late appearance shifts the calendar grid mid-test and breaks click
     * coordinates.
     */
    protected fun futureDateInCurrentMonth(): LocalDate {
        val today = LocalDate.now()
        return if (today.dayOfMonth < today.lengthOfMonth()) today.plusDays(1) else today
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

    /** App context forced to English — the locale the seed template is resolved in. */
    private val english: Context by lazy {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList(Locale.ENGLISH))
        app.createConfigurationContext(config)
    }

    /**
     * The label a SEEDED event-type preset carries in Room, as the UI renders it.
     *
     * ADR-032 §3 seeds presets from the catalog under an English-forced configuration
     * (cross-client uniformity with the server migration rows), and the booking form /
     * picker / card print `preset.label` verbatim (§5 snapshot semantics) — so under the
     * `hi` run the row still reads "Lagan", never "लगन". Use this instead of [string] for
     * any assertion on a seeded preset's name; [string] is only for catalog UI chrome.
     */
    protected fun seededPresetLabel(
        @StringRes id: Int,
    ): String = english.getString(id)

    /** The locale the app FORMATS dates in under [testLocale] (Hinglish → en-IN, ADR-110). */
    protected val formatLocale: Locale get() = FormatLocale.of(testLocale)

    /** Mirrors `feature:booking` Formatting.formatDate (MEDIUM style, app formatting locale). */
    protected fun formatDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(formatLocale))

    /** Mirrors Formatting.formatFullDate — the calendar day-cell a11y prefix. */
    protected fun formatFullDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(formatLocale))

    // ---- synchronization helpers ----

    /**
     * True when [nodes] currently matches at least one node. A poll instant with NO
     * compose hierarchy at all — the activity is still launching, or is being RECREATED
     * by the per-app-locale application on a cold emulator image — counts as "not yet"
     * instead of aborting the enclosing `waitUntil` with `IllegalStateException`
     * ("No compose hierarchies found in the app").
     */
    private fun hasAnyNode(nodes: androidx.compose.ui.test.SemanticsNodeInteractionCollection): Boolean =
        try {
            nodes.fetchSemanticsNodes().isNotEmpty()
        } catch (_: IllegalStateException) {
            false
        }

    protected fun waitForText(
        text: String,
        substring: Boolean = false,
        timeoutMillis: Long = UI_TIMEOUT_MS,
    ): SemanticsNodeInteraction {
        try {
            compose.waitUntil(timeoutMillis) {
                hasAnyNode(compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true))
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
                hasAnyNode(compose.onAllNodesWithContentDescription(description, substring = substring, useUnmergedTree = true))
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
            hasAnyNode(compose.onAllNodes(matcher, useUnmergedTree = true))
        }
        return compose.onAllNodes(matcher, useUnmergedTree = true).onFirst()
    }

    /**
     * The notification posted under [tag]/[id], once the system has actually posted it.
     *
     * `NotificationManager.notify()` only hands the record to the system; the notification
     * manager service enqueues and posts it on its own handler thread (a further ~200 ms
     * behind when a notification assistant is installed, as on every emulator image), so
     * `activeNotifications` read straight after `notify()` is EMPTY on a loaded CI runner.
     * That read was the Oct 2026 nightly's recurring `NoSuchElementException` in
     * `ReminderDeepLink*Test.warm_…`; matching on the id too keeps a stale reminder of an
     * earlier test from being mistaken for this one.
     */
    protected fun awaitNotification(
        tag: String,
        id: Int,
        timeoutMillis: Long = UI_TIMEOUT_MS,
    ): StatusBarNotification {
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(NotificationManager::class.java)
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (true) {
            manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) {
                "notification tag=$tag id=$id was not posted within ${timeoutMillis}ms; shade: " +
                    manager.activeNotifications.joinToString { "${it.tag}/${it.id}" }
            }
            Thread.sleep(NOTIFICATION_POLL_MS)
        }
    }

    /**
     * Click that lands where the node IS, not where it WAS.
     *
     * `performClick()` reads the node's bounds on the test thread and injects the touch on
     * the main thread a moment later. Whenever the layout is still moving in between — the
     * soft keyboard arriving after a text field took focus shifts every `imePadding()`-pinned
     * action row by the keyboard height; a sheet or dialog settling — the touch is injected
     * at the OLD coordinates and hits nothing (observed: `BookingFlowHiTest.tentativeBooking_…`
     * on the CI emulator, where frames take ~500 ms; the Save tap right after the first
     * `performTextInput` landed in the keyboard gap below the row). This waits until the
     * node's window bounds are unchanged across a REAL rendered frame plus Compose idling
     * before injecting, so the coordinates it uses are the ones on screen.
     */
    protected fun SemanticsNodeInteraction.tap(): SemanticsNodeInteraction {
        awaitStableBounds()
        performClick()
        return this
    }

    private fun SemanticsNodeInteraction.awaitStableBounds(timeoutMillis: Long = UI_TIMEOUT_MS) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        compose.waitForIdle()
        var bounds = fetchSemanticsNode().boundsInWindow
        while (true) {
            awaitRenderedFrame()
            compose.waitForIdle()
            val next = fetchSemanticsNode().boundsInWindow
            if (next == bounds) return
            check(SystemClock.uptimeMillis() < deadline) { "node kept moving for ${timeoutMillis}ms: $bounds -> $next" }
            bounds = next
        }
    }

    /** Blocks until the main thread has rendered one more frame (Choreographer vsync). */
    private fun awaitRenderedFrame() {
        val frame = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Choreographer.getInstance().postFrameCallback { frame.countDown() }
        }
        check(frame.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "no frame rendered within ${FRAME_TIMEOUT_MS}ms" }
    }

    /**
     * Types into a text field and waits for the soft keyboard its focus requested to be
     * laid out before returning, so the next interaction sees the keyboard-shifted layout
     * (see [tap]). The keyboard arrives asynchronously from the IME process; on a fast
     * machine that is one frame, on the CI emulator hundreds of milliseconds — exactly the
     * window in which a follow-up tap used to be injected at stale coordinates. Gives up
     * quietly after [IME_SHOW_TIMEOUT_MS] (a device with a hardware keyboard shows none).
     */
    protected fun SemanticsNodeInteraction.typeText(text: String): SemanticsNodeInteraction {
        performTextInput(text)
        awaitImeShown()
        compose.waitForIdle()
        return this
    }

    private fun awaitImeShown() {
        val start = SystemClock.uptimeMillis()
        val deadline = start + IME_SHOW_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (isImeVisible()) {
                android.util.Log.d("SamarohE2e", "soft keyboard laid out ${SystemClock.uptimeMillis() - start}ms after text input")
                return
            }
            Thread.sleep(LOCALE_POLL_MS)
        }
        android.util.Log.w("SamarohE2e", "soft keyboard did not appear within ${IME_SHOW_TIMEOUT_MS}ms after text input")
    }

    private fun isImeVisible(): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = currentActivityOrNull()?.window?.decorView ?: return@runOnMainSync
            visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun currentActivityOrNull(): android.app.Activity? =
        ActivityLifecycleMonitorRegistry
            .getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .firstOrNull()

    protected fun waitUntilGone(
        text: String,
        substring: Boolean = false,
        timeoutMillis: Long = UI_TIMEOUT_MS,
    ) {
        compose.waitUntil(timeoutMillis) {
            !hasAnyNode(compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true))
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
