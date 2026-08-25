package com.itsluminous.samaroh.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

/**
 * Typography tuned for the primary persona (§6): body text is never below 16sp.
 * Everything else follows Material 3 defaults.
 */
private val SamarohTypography =
    Typography().let { base ->
        base.copy(
            bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodySmall = base.bodySmall.copy(fontSize = 16.sp, lineHeight = 24.sp),
            labelLarge = base.labelLarge.copy(fontSize = 16.sp),
        )
    }

private val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }

/** Access point for semantic colors (moneyIn/moneyOut/tentative). */
object SamarohTheme {
    val semanticColors: SemanticColors
        @Composable @ReadOnlyComposable
        get() = LocalSemanticColors.current
}

/**
 * App theme: Material You dynamic color on Android 12+, curated #6750A4 fallback palette
 * everywhere else (shared/brand/palette.md).
 */
@Composable
fun SamarohTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SamarohTypography,
            content = content,
        )
    }
}
