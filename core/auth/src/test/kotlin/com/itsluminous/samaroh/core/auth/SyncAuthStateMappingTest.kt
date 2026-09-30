package com.itsluminous.samaroh.core.auth

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.SyncAuthState
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import org.junit.Test

/** ADR-089: the persisted last-signed-in marker turns "no session" into a lost session or plain offline mode. */
class SyncAuthStateMappingTest {
    private val session = UserSession(accessToken = "a", refreshToken = "r", expiresIn = 3600, tokenType = "bearer", user = null)

    @Test
    fun `authenticated is signed in regardless of marker`() {
        val status = SessionStatus.Authenticated(session, SessionSource.Storage)
        assertThat(SupabaseAuthManager.deriveAuthState(status, null)).isEqualTo(SyncAuthState.SIGNED_IN)
        assertThat(SupabaseAuthManager.deriveAuthState(status, "u1")).isEqualTo(SyncAuthState.SIGNED_IN)
    }

    @Test
    fun `no session after a previous sign-in is a LOST session`() {
        val dropped = SessionStatus.NotAuthenticated(isSignOut = false)
        assertThat(SupabaseAuthManager.deriveAuthState(dropped, "u1")).isEqualTo(SyncAuthState.SIGNED_OUT)
    }

    @Test
    fun `no session on a device that never signed in is offline mode`() {
        val never = SessionStatus.NotAuthenticated(isSignOut = false)
        assertThat(SupabaseAuthManager.deriveAuthState(never, null)).isEqualTo(SyncAuthState.NO_ACCOUNT)
    }

    @Test
    fun `isSignOut is ignored - the library's revoked-refresh wipe also reports true`() {
        val wiped = SessionStatus.NotAuthenticated(isSignOut = true)
        assertThat(SupabaseAuthManager.deriveAuthState(wiped, "u1")).isEqualTo(SyncAuthState.SIGNED_OUT)
        // The user's own sign-out is told apart by the cleared marker, not the flag.
        assertThat(SupabaseAuthManager.deriveAuthState(wiped, null)).isEqualTo(SyncAuthState.NO_ACCOUNT)
    }

    @Test
    fun `initializing follows the marker so the shell does not flash a banner`() {
        assertThat(SupabaseAuthManager.deriveAuthState(SessionStatus.Initializing, "u1")).isEqualTo(SyncAuthState.SIGNED_IN)
        assertThat(SupabaseAuthManager.deriveAuthState(SessionStatus.Initializing, null)).isEqualTo(SyncAuthState.NO_ACCOUNT)
    }

    @Test
    fun `transient refresh failure keeps the session but blocks the run`() {
        val failure = SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(RuntimeException("offline")))
        assertThat(SupabaseAuthManager.deriveAuthState(failure, "u1")).isEqualTo(SyncAuthState.REFRESH_PENDING)
    }
}
