package com.itsluminous.samaroh.core.auth

import com.itsluminous.samaroh.core.model.MemberPermissions
import kotlinx.coroutines.flow.Flow

/*
 * Auth module shell (Wave 0). Supabase-backed implementations (auth flows, session
 * persistence, invite acceptance) are the W1-D deliverable.
 */

/** Snapshot of the signed-in user. */
data class Session(
    val userId: String,
    val email: String,
)

/** Holds the current auth session; single source of truth for "who is signed in". */
interface SessionHolder {
    /** Emits the current session, or null when signed out. */
    val session: Flow<Session?>

    suspend fun signOut()
}

/**
 * App-layer permission checks (§3 layer 2): hides/greys UI the user cannot use.
 * NEVER security by itself — Postgres RLS is the authoritative layer.
 */
interface PermissionGuard {
    /** Emits the effective permissions of the current user in [businessId]; owners get full access. */
    fun permissions(businessId: String): Flow<MemberPermissions>

    /** Emits whether the current user is the owner of [businessId] (backups are owner-only). */
    fun isOwner(businessId: String): Flow<Boolean>
}
