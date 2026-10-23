package com.itsluminous.samaroh

import android.app.assist.AssistContent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.itsluminous.samaroh.applink.AppLink
import com.itsluminous.samaroh.core.data.share.ShareIntakeHolder
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.help.AssistContextProvider
import com.itsluminous.samaroh.core.help.HelpGuideSource
import com.itsluminous.samaroh.feature.booking.reminders.BookingDeepLink
import com.itsluminous.samaroh.feature.booking.reminders.BookingDeepLinkHolder
import com.itsluminous.samaroh.feature.booking.reminders.BookingDeepLinks
import com.itsluminous.samaroh.share.ShareIntents
import com.itsluminous.samaroh.ui.MainViewModel
import com.itsluminous.samaroh.ui.SamarohApp
import com.itsluminous.samaroh.ui.ThemePrefs
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity shell. Extends [AppCompatActivity] (not ComponentActivity) because the
 * per-app locale backport (§5, `AppCompatDelegate.setApplicationLocales`) requires an
 * AppCompat activity to recreate on language change.
 *
 * Wave-1 wiring: theme prefs from the shared settings DataStore feed [SamarohTheme];
 * booking-reminder notifications (and the full-screen popup's View) relaunch this activity
 * with a [BookingDeepLink] and the shell routes to that booking's card (§4.1 deep link,
 * ADR-098) — arrival also acknowledges the delivery (ADR-094). Android App Links (ADR-033):
 * `https://samaroh-web.vercel.app/…` VIEW intents are parsed into an [AppLink] the shell
 * routes on, both cold start ([onCreate]) and warm ([onNewIntent], `singleTask`).
 * Assistant context (ADR-108): [onProvideAssistContent] hands the default assistant the
 * public help page + JSON-LD of the screen on display, in the app language, never user data.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** Parks the share-sheet payload for the unified chooser (ADR-086). */
    @Inject lateinit var shareIntakeHolder: ShareIntakeHolder

    /** Parks the reminder deep link for the Booking feature (ADR-098) — never Compose state, see the holder. */
    @Inject lateinit var bookingDeepLinks: BookingDeepLinkHolder

    /** Builds the per-screen assistant payload from the bundled guide (ADR-108). */
    @Inject lateinit var assistContextProvider: AssistContextProvider

    /** The bundled guide — warmed off the main thread so the first assist call never parses. */
    @Inject lateinit var helpGuideSource: HelpGuideSource

    /** Web App-Link destination from the latest VIEW intent, cleared once routed. */
    private var pendingAppLink by mutableStateOf<AppLink?>(null)

    /** Files arrived via the Save to Samaroh share target (ADR-086), cleared once the chooser routed them. */
    private var pendingShare by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // AndroidX splash (Theme.Samaroh.Splash): must be installed before
        // super.onCreate() so the handoff to postSplashScreenTheme is seamless.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consumeBookingDeepLink(intent)
        pendingAppLink = appLinkFrom(intent)
        consumeShareTarget(intent)
        helpGuideSource.preload(lifecycleScope)
        setContent {
            val themePrefs by viewModel.themePrefs.collectAsStateWithLifecycle()
            SamarohTheme(
                darkTheme =
                    when (themePrefs.themeMode) {
                        ThemePrefs.THEME_LIGHT -> false
                        ThemePrefs.THEME_DARK -> true
                        else -> isSystemInDarkTheme()
                    },
                dynamicColor = themePrefs.dynamicColor,
            ) {
                SamarohApp(
                    pendingAppLink = pendingAppLink,
                    onAppLinkConsumed = { pendingAppLink = null },
                    pendingShare = pendingShare,
                    onShareConsumed = { pendingShare = false },
                )
            }
        }
    }

    /**
     * Assistant context (ADR-108, contract `shared/docs/assistant-context.md`): the help page
     * of the CURRENT screen (route + open sheet, from `CurrentScreenTracker`) in the APP
     * language as the web URI, plus the screen's guide tasks as compact JSON-LD (≤ 8 KB).
     * Tasks the member cannot perform are left out. Only public guide text, the screen id
     * and the language leave the device — never customer names, phones, amounts or ids.
     * Unknown screen → the guide index.
     */
    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        val member = viewModel.shareContext.value
        val context =
            assistContextProvider.current(
                appLocale = resources.configuration.locales[0],
                isOwner = member?.isOwner ?: true,
                permissions = member?.permissions,
            )
        outContent.webUri = Uri.parse(context.pageUrl)
        outContent.structuredData = context.jsonLd
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeBookingDeepLink(intent)
        appLinkFrom(intent)?.let { pendingAppLink = it }
        consumeShareTarget(intent)
    }

    /**
     * Reminder tap / popup View (ADR-098): parks the link for the shell and acknowledges
     * the delivery on arrival (ADR-094 — idempotent with the notification's deleteIntent
     * ack; covers the action tap that leaves the notification in the shade).
     */
    private fun consumeBookingDeepLink(intent: Intent?) {
        val link = BookingDeepLink.from(intent) ?: return
        BookingDeepLinks.acknowledgeOpened(this, link)
        bookingDeepLinks.set(link)
    }

    /** Parses a VIEW intent's data URI into its [AppLink]; null for non-link intents. */
    private fun appLinkFrom(intent: Intent?): AppLink? =
        intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW }?.let { AppLink.parse(it.path) }

    /**
     * Save to Samaroh share target (ADR-086): `ACTION_SEND`/`ACTION_SEND_MULTIPLE` of any
     * MIME (cold start via [onCreate], warm via [onNewIntent] — `singleTask`) parks the
     * files in the holder and raises the flag; the shell shows the chooser.
     */
    private fun consumeShareTarget(intent: Intent?) {
        val shared = ShareIntents.parse(this, intent)
        if (shared.isEmpty()) return
        shareIntakeHolder.set(shared)
        pendingShare = true
    }
}
