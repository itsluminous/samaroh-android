package com.itsluminous.samaroh

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.ui.SamarohApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity shell. Extends [AppCompatActivity] (not ComponentActivity) because the
 * per-app locale backport (§5, `AppCompatDelegate.setApplicationLocales`) requires an
 * AppCompat activity to recreate on language change.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SamarohTheme {
                SamarohApp()
            }
        }
    }
}
