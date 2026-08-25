package com.itsluminous.samaroh.feature.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WavingHand
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.samaroh.core.designsystem.component.PlaceholderScreen
import com.itsluminous.samaroh.core.i18n.R

/** Route of the onboarding flow's start destination (not a bottom tab). */
const val ONBOARDING_ROUTE = "onboarding"

/**
 * Onboarding feature graph (Wave 0 skeleton — W1-D implements language pick, welcome
 * carousel, sign-in, business create/join and the Google-link step).
 */
fun NavGraphBuilder.onboardingGraph() {
    composable(ONBOARDING_ROUTE) {
        PlaceholderScreen(featureNameRes = R.string.app_feature_onboarding, icon = Icons.Filled.WavingHand)
    }
}
