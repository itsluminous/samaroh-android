package com.itsluminous.samaroh.core.auth

/** Why an auth call failed — the UI maps each kind to a localized message. */
enum class AuthFailureKind {
    /** Supabase URL/key are missing from this build — auth is unavailable. */
    NOT_CONFIGURED,

    /** The server rejected the credentials (wrong password, duplicate email, weak password…). */
    REJECTED,

    /** The device could not reach the server. */
    NETWORK,
}

sealed interface AuthResult {
    data object Success : AuthResult

    data class Failure(
        val kind: AuthFailureKind,
    ) : AuthResult
}

/**
 * Auth entry points (spec §3: identity = email; Supabase Auth email+password and
 * Sign in with Google). Session state is observed via [SessionHolder].
 */
interface AuthRepository {
    /** Whether this build can talk to an auth backend at all. */
    val isConfigured: Boolean

    suspend fun signInWithEmail(
        email: String,
        password: String,
    ): AuthResult

    suspend fun signUpWithEmail(
        email: String,
        password: String,
    ): AuthResult

    /** Completes Sign-in with Google using an ID token obtained via Credential Manager. */
    suspend fun signInWithGoogleIdToken(idToken: String): AuthResult
}
