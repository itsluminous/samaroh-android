package com.itsluminous.samaroh.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.itsluminous.samaroh.feature.onboarding.ui.CreateBusinessScreen
import com.itsluminous.samaroh.feature.onboarding.ui.ForkScreen
import com.itsluminous.samaroh.feature.onboarding.ui.JoinScreen
import com.itsluminous.samaroh.feature.onboarding.ui.LanguageScreen
import com.itsluminous.samaroh.feature.onboarding.ui.LinkGoogleScreen
import com.itsluminous.samaroh.feature.onboarding.ui.SignInScreen
import com.itsluminous.samaroh.feature.onboarding.ui.WelcomeScreen

/** Nav argument: start the flow at the sign-in step (post-sign-out re-entry, ADR-040). */
internal const val ONBOARDING_ARG_START_AT_SIGN_IN = "startAtSignIn"

/**
 * Route (pattern) of the onboarding flow's start destination (not a bottom tab). The
 * optional [ONBOARDING_ARG_START_AT_SIGN_IN] argument defaults to false, so plain
 * navigation still begins at the language step.
 */
const val ONBOARDING_ROUTE = "onboarding?$ONBOARDING_ARG_START_AT_SIGN_IN={$ONBOARDING_ARG_START_AT_SIGN_IN}"

/**
 * Route that opens onboarding directly at the sign-in step — the post-sign-out landing
 * (ADR-040): the user already picked a language, so re-running the language/welcome
 * steps would be noise.
 */
const val ONBOARDING_SIGN_IN_ROUTE = "onboarding?$ONBOARDING_ARG_START_AT_SIGN_IN=true"

/**
 * Onboarding feature graph (§4.0): language pick → welcome carousel → sign-in →
 * create-vs-join fork → create-business/join → link-Google → done.
 *
 * @param onOnboardingComplete invoked once the flow reaches its final step — the app
 *   shell navigates to the Booking tab (§4.0 step 7). Defaults keep the Wave 0 call
 *   site (`onboardingGraph()`) source-compatible until the integrator wires it.
 * @param onConnectGoogle seam for the W1-F Google account linking flow (Drive/Calendar
 *   scopes); onboarding itself never blocks on it ("Do it later" is prominent).
 */
fun NavGraphBuilder.onboardingGraph(
    onOnboardingComplete: () -> Unit = {},
    onConnectGoogle: () -> Unit = {},
) {
    composable(
        route = ONBOARDING_ROUTE,
        arguments =
            listOf(
                navArgument(ONBOARDING_ARG_START_AT_SIGN_IN) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
    ) {
        OnboardingRoute(onOnboardingComplete = onOnboardingComplete, onConnectGoogle = onConnectGoogle)
    }
}

@Composable
internal fun OnboardingRoute(
    onOnboardingComplete: () -> Unit,
    onConnectGoogle: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = state.step != OnboardingStep.LANGUAGE && state.step != OnboardingStep.DONE) {
        viewModel.goBack()
    }

    LaunchedEffect(state.step) {
        if (state.step == OnboardingStep.DONE) onOnboardingComplete()
    }

    when (state.step) {
        OnboardingStep.LANGUAGE ->
            LanguageScreen(
                state = state,
                onLanguageSelected = viewModel::selectLanguage,
                onContinue = viewModel::continueFromLanguage,
            )
        OnboardingStep.WELCOME ->
            WelcomeScreen(onFinished = viewModel::finishWelcome)
        OnboardingStep.SIGN_IN ->
            SignInScreen(
                state = state,
                onModeChange = viewModel::setAuthMode,
                onSubmit = viewModel::submitEmailAuth,
                onGoogleSignIn = viewModel::signInWithGoogle,
                onContinueOffline = viewModel::continueWithoutAccount,
            )
        OnboardingStep.FORK ->
            ForkScreen(
                state = state,
                onCreate = viewModel::chooseCreate,
                onJoin = viewModel::chooseJoin,
            )
        OnboardingStep.JOIN ->
            JoinScreen(
                state = state,
                onAccept = viewModel::acceptInvite,
                onRefresh = viewModel::refreshInvites,
            )
        OnboardingStep.CREATE_BUSINESS ->
            CreateBusinessScreen(
                state = state,
                onFormChange = viewModel::updateForm,
                onLogoCropped = viewModel::onLogoCropped,
                onSubmit = viewModel::submitCreateBusiness,
            )
        OnboardingStep.LINK_GOOGLE ->
            LinkGoogleScreen(
                onConnect = {
                    onConnectGoogle()
                    viewModel.finishOnboarding()
                },
                onLater = viewModel::finishOnboarding,
            )
        OnboardingStep.DONE -> Unit
    }
}
