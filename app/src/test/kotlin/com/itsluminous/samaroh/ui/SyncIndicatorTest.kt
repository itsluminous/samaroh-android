package com.itsluminous.samaroh.ui

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.SyncAuthState
import org.junit.Test

/** ADR-089: when the app-bar cloud icon must show the "sign in to sync" state instead of the green check. */
class SyncIndicatorTest {
    @Test
    fun `a lost session always needs sign-in, even with an empty queue`() {
        assertThat(SyncIndicator(authState = SyncAuthState.SIGNED_OUT).needsSignIn).isTrue()
        assertThat(SyncIndicator(pendingCount = 3, authState = SyncAuthState.SIGNED_OUT).needsSignIn).isTrue()
    }

    @Test
    fun `offline mode needs sign-in only once changes are queued`() {
        assertThat(SyncIndicator(authState = SyncAuthState.NO_ACCOUNT).needsSignIn).isFalse()
        assertThat(SyncIndicator(pendingCount = 1, authState = SyncAuthState.NO_ACCOUNT).needsSignIn).isTrue()
    }

    @Test
    fun `signed in, refreshing and unconfigured never show the sign-in state`() {
        for (state in listOf(SyncAuthState.SIGNED_IN, SyncAuthState.REFRESH_PENDING, SyncAuthState.NOT_CONFIGURED)) {
            assertThat(SyncIndicator(pendingCount = 5, errorCount = 2, authState = state).needsSignIn).isFalse()
        }
    }
}
