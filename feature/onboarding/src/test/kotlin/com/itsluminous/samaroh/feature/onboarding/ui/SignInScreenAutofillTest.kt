package com.itsluminous.samaroh.feature.onboarding.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import com.itsluminous.samaroh.feature.onboarding.AuthFormMode
import com.itsluminous.samaroh.feature.onboarding.OnboardingUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Password managers only offer autofill when the Compose fields expose autofill
 * ContentType semantics — this pins them on the sign-in AND sign-up forms (the same
 * two fields serve both modes).
 */
@RunWith(RobolectricTestRunner::class)
class SignInScreenAutofillTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * Matches nodes whose ContentType semantics carry the given Android autofill hint.
     * `AndroidContentType` has no value equality (each `+` creates a fresh instance), so
     * the matcher reads its `androidAutofillHints` set reflectively instead.
     */
    private fun hasAutofillHint(hint: String): SemanticsMatcher =
        SemanticsMatcher("has autofill hint '$hint'") { node ->
            val contentType = node.config.getOrNull(SemanticsProperties.ContentType) ?: return@SemanticsMatcher false
            val method = contentType.javaClass.getMethod("getAndroidAutofillHints").apply { isAccessible = true }
            (method.invoke(contentType) as Set<*>).contains(hint)
        }

    private fun setScreen(mode: AuthFormMode) {
        compose.setContent {
            SignInScreen(
                state = OnboardingUiState(authMode = mode),
                onModeChange = {},
                onSubmit = { _, _ -> },
                onGoogleSignIn = {},
                onContinueOffline = {},
            )
        }
    }

    @Test
    fun signInFieldsExposeAutofillContentTypes() {
        setScreen(AuthFormMode.SIGN_IN)

        // Email field advertises BOTH the email-address and username hints.
        compose.onAllNodes(hasAutofillHint("emailAddress")).assertCountEquals(1)
        compose.onAllNodes(hasAutofillHint("username")).assertCountEquals(1)
        compose.onAllNodes(hasAutofillHint("password")).assertCountEquals(1)
    }

    @Test
    fun signUpFieldsExposeAutofillContentTypes() {
        setScreen(AuthFormMode.SIGN_UP)

        compose.onAllNodes(hasAutofillHint("emailAddress")).assertCountEquals(1)
        compose.onAllNodes(hasAutofillHint("username")).assertCountEquals(1)
        // Sign-up marks the password as NEW so managers offer password generation.
        compose.onAllNodes(hasAutofillHint("newPassword")).assertCountEquals(1)
    }
}
