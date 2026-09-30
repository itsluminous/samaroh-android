package com.itsluminous.samaroh.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.itsluminous.samaroh.core.data.settings.SettingsDataStore
import com.itsluminous.samaroh.core.data.sync.SyncAuthGate
import com.itsluminous.samaroh.core.data.sync.SyncAuthState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supabase-backed [SessionHolder], [AuthRepository] and [SyncAuthGate] (W1-D, ADR-089).
 *
 * Session persistence across process restarts is handled by supabase-kt's default
 * Android session manager (SharedPreferences-backed) with automatic token refresh, so
 * "who is signed in" survives restarts and phone reboots — account-based, never
 * device-locked (§3). When Supabase is not configured ([client] is null) every call
 * degrades to [AuthFailureKind.NOT_CONFIGURED] and the session flow is permanently null.
 *
 * ADR-089: supabase-kt CLEARS the stored session on a definitive refresh failure (a
 * revoked/expired refresh token → HTTP 4xx), leaving the process with no session and no
 * event the UI could distinguish from "never signed in". This class therefore remembers
 * the last signed-in user in the settings DataStore ([KEY_LAST_SIGNED_IN_USER]): a
 * missing session WITH that marker is [SyncAuthState.SIGNED_OUT] (session lost — banner,
 * hold the outbox), without it [SyncAuthState.NO_ACCOUNT] (offline owner mode). An
 * explicit [signOut] clears the marker.
 */
@Singleton
class SupabaseAuthManager
    @Inject
    constructor(
        private val client: SupabaseClient?,
        @SettingsDataStore private val settings: DataStore<Preferences>,
    ) : SessionHolder,
        AuthRepository,
        SyncAuthGate {
        override val isConfigured: Boolean = client != null

        override val session: Flow<Session?> =
            client?.auth?.sessionStatus?.map { status ->
                when (status) {
                    is SessionStatus.Authenticated -> status.session.user?.toSession()
                    // A refresh that failed TRANSIENTLY (offline, 5xx) keeps the stored
                    // session — the user is still signed in, the library retries.
                    is SessionStatus.RefreshFailure ->
                        client.auth
                            .currentSessionOrNull()
                            ?.user
                            ?.toSession()
                    else -> null
                }
            } ?: flowOf(null)

        private val lastSignedInUser: Flow<String?> = settings.data.map { it[KEY_LAST_SIGNED_IN_USER] }

        override val authState: Flow<SyncAuthState> =
            client?.let { supabase ->
                combine(supabase.auth.sessionStatus, lastSignedInUser) { status, marker ->
                    deriveAuthState(status, marker)
                }
            } ?: flowOf(SyncAuthState.NOT_CONFIGURED)

        override suspend fun awaitAuthState(): SyncAuthState {
            val supabase = client ?: return SyncAuthState.NOT_CONFIGURED
            // Blocks until the stored session is loaded (and refreshed if expired) — the
            // very first sync run used to race this and pull as `anon`.
            supabase.auth.awaitInitialization()
            val status = supabase.auth.sessionStatus.value
            if (status is SessionStatus.Authenticated) rememberSignedIn(status.session.user?.id)
            return deriveAuthState(status, lastSignedInUser.first())
        }

        override suspend fun signOut() {
            val supabase = client ?: return
            try {
                supabase.auth.signOut()
            } catch (e: Exception) {
                // Offline-first (§5): the server-side token revoke can fail without
                // network, but sign-out must still complete locally — drop the persisted
                // session so the device is signed out; the token expires server-side.
                supabase.auth.clearSession()
            } finally {
                // Deliberate sign-out is not a lost session: no "signed out" banner.
                settings.edit { it.remove(KEY_LAST_SIGNED_IN_USER) }
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
                rememberSignedIn(
                    supabase.auth
                        .currentSessionOrNull()
                        ?.user
                        ?.id,
                )
                AuthResult.Success
            } catch (e: RestException) {
                AuthResult.Failure(AuthFailureKind.REJECTED)
            } catch (e: Exception) {
                AuthResult.Failure(AuthFailureKind.NETWORK)
            }
        }

        private suspend fun rememberSignedIn(userId: String?) {
            if (userId == null) return
            settings.edit { prefs ->
                if (prefs[KEY_LAST_SIGNED_IN_USER] != userId) prefs[KEY_LAST_SIGNED_IN_USER] = userId
            }
        }

        private fun io.github.jan.supabase.auth.user.UserInfo.toSession() = Session(userId = id, email = email.orEmpty())

        companion object {
            /** Settings key: user id of the account last signed in on this device (ADR-089). */
            val KEY_LAST_SIGNED_IN_USER = stringPreferencesKey("auth_last_signed_in_user_id")

            /**
             * Pure state mapping (unit-tested). [lastSignedInUser] is the persisted marker —
             * it turns "no session" into either a lost session or plain offline mode.
             */
            fun deriveAuthState(
                status: SessionStatus,
                lastSignedInUser: String?,
            ): SyncAuthState =
                when (status) {
                    is SessionStatus.Authenticated -> SyncAuthState.SIGNED_IN
                    // Storage load / refresh in flight: assume the marker's answer so the
                    // shell shows no banner flash; the engine awaits the settled state.
                    is SessionStatus.Initializing ->
                        if (lastSignedInUser != null) SyncAuthState.SIGNED_IN else SyncAuthState.NO_ACCOUNT
                    // Transient refresh failure (offline / 5xx): the session is kept and
                    // retried, but its access token may be stale — no network this run.
                    is SessionStatus.RefreshFailure -> SyncAuthState.REFRESH_PENDING
                    // NOTE: `isSignOut` cannot tell the cases apart — supabase-kt's
                    // clearSession() reports true for BOTH the user's sign-out and the
                    // revoked-refresh wipe. Only the marker does: signOut() clears it.
                    is SessionStatus.NotAuthenticated ->
                        if (lastSignedInUser != null) SyncAuthState.SIGNED_OUT else SyncAuthState.NO_ACCOUNT
                }
        }
    }
