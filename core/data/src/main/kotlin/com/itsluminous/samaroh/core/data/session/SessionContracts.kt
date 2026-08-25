package com.itsluminous.samaroh.core.data.session

import com.itsluminous.samaroh.core.model.Business
import kotlinx.coroutines.flow.Flow

/*
 * Active-business + current-user session contract — ADDITIVE Wave-1 integration seam
 * (docs/decisions.md ADR-017). Every feature previously carried its own "which business /
 * which user" assumption (booking's actor provider, expenses' session defaults, the
 * inventory and menu first-business lookups); this contract unifies them behind one pair
 * of flows implemented in `core:auth` on top of `SessionHolder`.
 *
 * Signed-out / offline default (current v1 behavior, §4.0 single-business): the first
 * live local business with OWNER semantics — the app is fully usable before sign-in.
 */

/** The business all tabs operate on. v1 is single-business; emits null before onboarding creates one. */
interface ActiveBusinessProvider {
    val activeBusiness: Flow<Business?>
}

/** The acting user. Emits null while signed out — consumers fall back to owner-mode (§3). */
interface CurrentUserProvider {
    /** Auth user id of the signed-in user, or null when signed out. */
    val currentUserId: Flow<String?>
}
