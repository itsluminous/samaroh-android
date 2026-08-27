package com.itsluminous.samaroh.feature.onboarding.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.itsluminous.samaroh.core.auth.AuthFailureKind
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.feature.onboarding.AuthFormMode
import com.itsluminous.samaroh.feature.onboarding.OnboardingUiState

/**
 * §4.0 step 3 — sign in: Google is the primary CTA, email+password below. The Google
 * button MUST degrade gracefully to a localized "not configured" state when this build
 * has no `GOOGLE_WEB_CLIENT_ID`.
 */
@Composable
internal fun SignInScreen(
    state: OnboardingUiState,
    onModeChange: (AuthFormMode) -> Unit,
    onSubmit: (email: String, password: String) -> Unit,
    onGoogleSignIn: (Context) -> Unit,
    onContinueOffline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val signUp = state.authMode == AuthFormMode.SIGN_UP

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(if (signUp) R.string.auth_sign_up_title else R.string.auth_sign_in_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.onboarding_signin_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        // Primary CTA: Sign in with Google — or its localized "not configured" state.
        if (state.googleSignInConfigured) {
            Button(
                onClick = { onGoogleSignIn(context) },
                enabled = !state.isBusy && state.supabaseConfigured,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.auth_sign_in_google))
            }
        } else {
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.auth_google_not_configured))
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.auth_sign_in_email_label)) },
            keyboardOptions =
                androidx.compose.foundation.text
                    .KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            enabled = state.supabaseConfigured,
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Password managers key on these hints to offer autofill (sign-in AND sign-up).
                    .semantics { contentType = ContentType.EmailAddress + ContentType.Username },
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.auth_sign_in_password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                androidx.compose.foundation.text
                    .KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            enabled = state.supabaseConfigured,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .semantics { contentType = if (signUp) ContentType.NewPassword else ContentType.Password },
        )

        if (!state.supabaseConfigured) {
            Text(
                text = stringResource(R.string.auth_sign_in_not_configured),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        state.authError?.let { error ->
            Text(
                text = authErrorText(error, signUp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Button(
            onClick = { onSubmit(email, password) },
            enabled = !state.isBusy && state.supabaseConfigured && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).defaultMinSize(minHeight = 48.dp),
        ) {
            Text(stringResource(if (signUp) R.string.auth_sign_up_submit else R.string.auth_sign_in_submit))
        }
        TextButton(
            onClick = { onModeChange(if (signUp) AuthFormMode.SIGN_IN else AuthFormMode.SIGN_UP) },
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        ) {
            Text(stringResource(if (signUp) R.string.auth_mode_to_sign_in else R.string.auth_mode_to_sign_up))
        }
        // Offline-first (§5): never a dead end — proceed in device-local owner mode.
        TextButton(
            onClick = onContinueOffline,
            enabled = !state.isBusy,
            modifier = Modifier.align(Alignment.CenterHorizontally).defaultMinSize(minHeight = 48.dp),
        ) {
            Text(stringResource(R.string.onboarding_sign_in_continue_offline))
        }
        if (state.isBusy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp))
        }
    }
}

@Composable
private fun authErrorText(
    error: AuthFailureKind,
    signUp: Boolean,
): String =
    stringResource(
        when (error) {
            AuthFailureKind.NOT_CONFIGURED -> R.string.auth_sign_in_not_configured
            AuthFailureKind.NETWORK -> R.string.auth_error_network
            AuthFailureKind.REJECTED -> if (signUp) R.string.auth_sign_up_error else R.string.auth_sign_in_error
        },
    )
