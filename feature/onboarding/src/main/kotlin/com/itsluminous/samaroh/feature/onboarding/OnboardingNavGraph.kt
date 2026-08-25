package com.itsluminous.samaroh.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.samaroh.feature.onboarding.ui.CreateBusinessScreen
import com.itsluminous.samaroh.feature.onboarding.ui.ForkScreen
import com.itsluminous.samaroh.feature.onboarding.ui.JoinScreen
import com.itsluminous.samaroh.feature.onboarding.ui.LanguageScreen
import com.itsluminous.samaroh.feature.onboarding.ui.LinkGoogleScreen
import com.itsluminous.samaroh.feature.onboarding.ui.SignInScreen
import com.itsluminous.samaroh.feature.onboarding.ui.WelcomeScreen

/** Route of the onboarding flow's start destination (not a bottom tab). */
const val ONBOARDING_ROUTE = "onboarding"

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
    composable(ONBOARDING_ROUTE) {
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
                onLogoCaptured = viewModel::onLogoCaptured,
                onLogoPicked = viewModel::onLogoPicked,
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
