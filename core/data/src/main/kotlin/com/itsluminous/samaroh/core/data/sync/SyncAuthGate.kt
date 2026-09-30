package com.itsluminous.samaroh.core.data.sync

import kotlinx.coroutines.flow.Flow

/**
 * Whether the sync engine is ALLOWED to talk to the server (ADR-089). Postgres RLS
 * evaluates every request as the bearer of the attached JWT; without a user session the
 * client sends only the anon key, so every push fails with `42501` and every pull filters
 * to nothing. The engine therefore never touches the network unless the state is
 * [SIGNED_IN] — queued changes wait locally and the UI says why.
 */
enum class SyncAuthState {
    /** No Supabase credentials in this build — sync is an offline-only no-op. */
    NOT_CONFIGURED,

    /** The device never had a user session ("Continue without an account" owner mode). */
    NO_ACCOUNT,

    /** A user session is attached — pushes and pulls run as that user. */
    SIGNED_IN,

    /**
     * The stored session could not be refreshed for a TRANSIENT reason (offline, server
     * 5xx); the auth library keeps it and retries. The engine skips the network this run
     * and lets WorkManager retry — the access token may be stale.
     */
    REFRESH_PENDING,

    /**
     * A user WAS signed in on this device and the session is gone without an explicit
     * sign-out — the auth library dropped it after a definitive refresh failure (revoked
     * or expired refresh token). Queued changes are held; the shell shows a sign-in banner.
     */
    SIGNED_OUT,
}

/** Read model of [SyncAuthState]; implemented in `core:auth` on the Supabase session. */
interface SyncAuthGate {
    /** Live state — drives the app-bar cloud icon, the shell banner and the Sync status screen. */
    val authState: Flow<SyncAuthState>

    /**
     * Sync pre-flight: waits for the auth library to finish loading (and, if needed,
     * refreshing) the stored session, then reports the settled state. Callers must never
     * hit the network while the library is still initializing — the first requests would
     * otherwise carry only the anon key even though a valid session is about to load.
     */
    suspend fun awaitAuthState(): SyncAuthState
}

/** Outbox `last_error` codes the engine writes for non-server conditions (ADR-089). */
object SyncErrorCodes {
    /**
     * The op is held because there is no user session. Not a server rejection: the Sync
     * status screen shows these rows as pending (with the sign-in banner), never as errors.
     */
    const val WAITING_FOR_SIGN_IN = "auth:waiting-for-sign-in"
}
