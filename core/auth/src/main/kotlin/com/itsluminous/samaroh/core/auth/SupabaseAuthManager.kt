package com.itsluminous.samaroh.core.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supabase-backed [SessionHolder] and [AuthRepository] (W1-D).
 *
 * Session persistence across process restarts is handled by supabase-kt's default
 * Android session manager (SharedPreferences-backed) with automatic token refresh, so
 * "who is signed in" survives restarts and phone reboots — account-based, never
 * device-locked (§3). When Supabase is not configured ([client] is null) every call
 * degrades to [AuthFailureKind.NOT_CONFIGURED] and the session flow is permanently null.
 */
@Singleton
class SupabaseAuthManager
    @Inject
    constructor(
        private val client: SupabaseClient?,
    ) : SessionHolder,
        AuthRepository {
        override val isConfigured: Boolean = client != null

        override val session: Flow<Session?> =
            client?.auth?.sessionStatus?.map { status ->
                when (status) {
                    is SessionStatus.Authenticated ->
                        status.session.user?.let { user ->
                            Session(userId = user.id, email = user.email.orEmpty())
                        }
                    else -> null
                }
            } ?: flowOf(null)

        override suspend fun signOut() {
            val supabase = client ?: return
            try {
                supabase.auth.signOut()
            } catch (e: Exception) {
                // Offline-first (§5): the server-side token revoke can fail without
                // network, but sign-out must still complete locally — drop the persisted
                // session so the device is signed out; the token expires server-side.
                supabase.auth.clearSession()
            }
        }

        override suspend fun signInWithEmail(
            email: String,
            password: String,
        ): AuthResult =
            authCall {
                it.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
            }

        override suspend fun signUpWithEmail(
            email: String,
            password: String,
        ): AuthResult =
            authCall {
                it.auth.signUpWith(Email) {
                    this.email = email
                    this.password = password
                }
            }

        override suspend fun signInWithGoogleIdToken(idToken: String): AuthResult =
            authCall {
                it.auth.signInWith(IDToken) {
                    this.idToken = idToken
                    provider = Google
                }
            }

        private suspend fun authCall(block: suspend (SupabaseClient) -> Unit): AuthResult {
            val supabase = client ?: return AuthResult.Failure(AuthFailureKind.NOT_CONFIGURED)
            return try {
                block(supabase)
                AuthResult.Success
            } catch (e: RestException) {
                AuthResult.Failure(AuthFailureKind.REJECTED)
            } catch (e: Exception) {
                AuthResult.Failure(AuthFailureKind.NETWORK)
            }
        }
    }
