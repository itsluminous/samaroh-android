package com.itsluminous.samaroh.core.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a Credential Manager Google sign-in attempt. */
sealed interface GoogleSignInOutcome {
    data class IdToken(
        val token: String,
    ) : GoogleSignInOutcome

    /** No `GOOGLE_WEB_CLIENT_ID` in this build — the UI shows the localized "not configured" state. */
    data object NotConfigured : GoogleSignInOutcome

    /** The user dismissed the credential sheet. Not an error. */
    data object Cancelled : GoogleSignInOutcome

    data class Failed(
        val cause: Throwable,
    ) : GoogleSignInOutcome
}

/**
 * Fetches a Google ID token with Credential Manager against `BuildConfig.GOOGLE_WEB_CLIENT_ID`
 * (spec §1.1). MUST degrade gracefully when the client id is empty: [invoke] returns
 * [GoogleSignInOutcome.NotConfigured] and never touches Credential Manager.
 */
@Singleton
class GoogleIdTokenFetcher
    @Inject
    constructor(
        private val config: AuthConfig,
    ) {
        val isConfigured: Boolean get() = config.isGoogleSignInConfigured

        /** [context] must be an Activity context — Credential Manager shows UI. */
        suspend operator fun invoke(context: Context): GoogleSignInOutcome {
            if (!isConfigured) return GoogleSignInOutcome.NotConfigured
            return try {
                val option =
                    GetGoogleIdOption
                        .Builder()
                        .setServerClientId(config.googleWebClientId)
                        .setFilterByAuthorizedAccounts(false)
                        .build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val credential = CredentialManager.create(context).getCredential(context, request).credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    GoogleSignInOutcome.IdToken(GoogleIdTokenCredential.createFrom(credential.data).idToken)
                } else {
                    GoogleSignInOutcome.Failed(IllegalStateException("Unexpected credential type: ${credential.type}"))
                }
            } catch (e: GetCredentialCancellationException) {
                GoogleSignInOutcome.Cancelled
            } catch (e: GetCredentialException) {
                GoogleSignInOutcome.Failed(e)
            }
        }
    }
