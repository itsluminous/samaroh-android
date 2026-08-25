package com.itsluminous.samaroh.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Curated fallback palette — exact tokens from shared/brand/palette.md (seed #6750A4).
 * Used everywhere dynamic color is unavailable (pre-Android 12).
 */

internal val LightColorScheme =
    lightColorScheme(
        primary = Color(0xFF6750A4),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEADDFF),
        onPrimaryContainer = Color(0xFF21005D),
        secondary = Color(0xFF625B71),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE8DEF8),
        onSecondaryContainer = Color(0xFF1D192B),
        tertiary = Color(0xFF7D5260),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFD8E4),
        onTertiaryContainer = Color(0xFF31111D),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        background = Color(0xFFFFFBFE),
        onBackground = Color(0xFF1C1B1F),
        surface = Color(0xFFFFFBFE),
        onSurface = Color(0xFF1C1B1F),
        surfaceVariant = Color(0xFFE7E0EC),
        onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF79747E),
    )

internal val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFD0BCFF),
        onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B),
        onPrimaryContainer = Color(0xFFEADDFF),
        secondary = Color(0xFFCCC2DC),
        onSecondary = Color(0xFF332D41),
        secondaryContainer = Color(0xFF4A4458),
        onSecondaryContainer = Color(0xFFE8DEF8),
        tertiary = Color(0xFFEFB8C8),
        onTertiary = Color(0xFF492532),
        tertiaryContainer = Color(0xFF633B48),
        onTertiaryContainer = Color(0xFFFFD8E4),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        background = Color(0xFF1C1B1F),
        onBackground = Color(0xFFE6E1E5),
        surface = Color(0xFF1C1B1F),
        onSurface = Color(0xFFE6E1E5),
        surfaceVariant = Color(0xFF49454F),
        onSurfaceVariant = Color(0xFFCAC4D0),
        outline = Color(0xFF938F99),
    )

/** Semantic tokens (both schemes) from shared/brand/palette.md. */
data class SemanticColors(
    /** "You got" / received amounts. */
    val moneyIn: Color,
    /** "You gave" / due amounts. */
    val moneyOut: Color,
    /** Tentative-booking amber (outlined pill). */
    val tentative: Color,
)

internal val LightSemanticColors =
    SemanticColors(
        moneyIn = Color(0xFF146C2E),
        moneyOut = Color(0xFFB3261E),
        tentative = Color(0xFF7A5900),
    )

internal val DarkSemanticColors =
    SemanticColors(
        moneyIn = Color(0xFF6DD58C),
        moneyOut = Color(0xFFF2B8B5),
        tentative = Color(0xFFF7BD48),
    )
