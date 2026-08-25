package com.itsluminous.samaroh.core.google.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.Flow

/** Current state of the Google account link for the signed-in user. */
sealed interface GoogleLinkState {
    /** [com.itsluminous.samaroh.core.google.GoogleServicesConfig] has no web client id — render the localized "not configured" state. */
    data object NotConfigured : GoogleLinkState

    /** Configured but no account linked yet. */
    data object NotLinked : GoogleLinkState

    /** An account is linked; [email] is shown in Settings (§4.4). */
    data class Linked(
        val email: String,
        val grantedScopes: List<String>,
    ) : GoogleLinkState
}

/** Failure modes of [GoogleAccountLinker.link] the UI must handle. */
sealed class GoogleLinkException(
    message: String,
) : Exception(message) {
    /** The web client id is blank — Google features are off (docs/google-setup.md). */
    class NotConfigured : GoogleLinkException("google web client id is not configured")

    /** No Supabase session — the `google_accounts` row is keyed by the auth user id. */
    class NotSignedIn : GoogleLinkException("no signed-in user to link the account to")

    /** The user must approve the incremental scopes; launch [pendingIntent] and call [GoogleAccountLinker.completeLink]. */
    class NeedsScopeConsent(
        val pendingIntent: PendingIntent,
    ) : GoogleLinkException("user consent required for requested scopes")

    /** User dismissed the account picker / consent sheet. */
    class Cancelled : GoogleLinkException("user cancelled the link flow")

    class Failed(
        cause: Throwable,
    ) : GoogleLinkException(cause.message ?: "google link failed") {
        init {
            initCause(cause)
        }
    }
}

/**
 * Google account linking via Credential Manager (§4.4 "Google account: link/unlink"),
 * requesting the incremental `drive.file` + `calendar.events` scopes (§9.1 least
 * privilege). The link is persisted in the `google_accounts` Room table (email/scopes
 * only — tokens never touch client storage, ADR-003).
 */
interface GoogleAccountLinker {
    /** Emits the current link state; UI renders Settings from this. */
    val linkState: Flow<GoogleLinkState>

    /**
     * Runs the account-picker + incremental-consent flow. [activityContext] MUST be an
     * Activity context (Credential Manager shows UI). May fail with
     * [GoogleLinkException.NeedsScopeConsent]; launch its intent sender and pass the
     * activity result to [completeLink].
     */
    suspend fun link(activityContext: Context): Result<GoogleLinkState.Linked>

    /** Completes a link after the consent UI returned [resultIntent]. */
    suspend fun completeLink(resultIntent: Intent?): Result<GoogleLinkState.Linked>

    /** Removes the local link row and clears credential state. Server-side revocation is not performed. */
    suspend fun unlink()
}
