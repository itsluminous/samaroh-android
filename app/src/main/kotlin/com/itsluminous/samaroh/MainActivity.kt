package com.itsluminous.samaroh

import android.content.Intent
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
import com.itsluminous.samaroh.applink.AppLink
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.feature.booking.reminders.EXTRA_BOOKING_ID
import com.itsluminous.samaroh.ui.MainViewModel
import com.itsluminous.samaroh.ui.SamarohApp
import com.itsluminous.samaroh.ui.ThemePrefs
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity shell. Extends [AppCompatActivity] (not ComponentActivity) because the
 * per-app locale backport (§5, `AppCompatDelegate.setApplicationLocales`) requires an
 * AppCompat activity to recreate on language change.
 *
 * Wave-1 wiring: theme prefs from the shared settings DataStore feed [SamarohTheme];
 * booking-reminder notifications relaunch this activity with [EXTRA_BOOKING_ID] and the
 * shell routes to that booking's card (§4.1 deep link). Android App Links (ADR-033):
 * `https://samaroh-web.vercel.app/…` VIEW intents are parsed into an [AppLink] the shell
 * routes on, both cold start ([onCreate]) and warm ([onNewIntent], `singleTask`).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** Booking id from the latest launch/new intent, cleared once the feature opened it. */
    private var pendingBookingId by mutableStateOf<String?>(null)

    /** Web App-Link destination from the latest VIEW intent, cleared once routed. */
    private var pendingAppLink by mutableStateOf<AppLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // AndroidX splash (Theme.Samaroh.Splash): must be installed before
        // super.onCreate() so the handoff to postSplashScreenTheme is seamless.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingBookingId = intent?.getStringExtra(EXTRA_BOOKING_ID)
        pendingAppLink = appLinkFrom(intent)
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
                    pendingBookingId = pendingBookingId,
                    onBookingDeepLinkConsumed = { pendingBookingId = null },
                    pendingAppLink = pendingAppLink,
                    onAppLinkConsumed = { pendingAppLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_BOOKING_ID)?.let { pendingBookingId = it }
        appLinkFrom(intent)?.let { pendingAppLink = it }
    }

    /** Parses a VIEW intent's data URI into its [AppLink]; null for non-link intents. */
    private fun appLinkFrom(intent: Intent?): AppLink? =
        intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW }?.let { AppLink.parse(it.path) }
}
