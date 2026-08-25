package com.itsluminous.samaroh.core.google.auth

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.itsluminous.samaroh.core.google.GoogleServicesConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies short-lived OAuth access tokens for the Drive/Calendar REST calls made by
 * background workers. Returns null when the app is not configured, no account is linked,
 * or the grant needs (re-)consent — workers must treat null as "retry later", never as an
 * error to surface.
 */
interface GoogleAccessTokenProvider {
    suspend fun accessToken(): String?
}

/**
 * Play services implementation: a silent authorization for already-granted scopes returns
 * a fresh access token without any UI. A result that needs resolution means consent was
 * revoked — the Settings link flow is the recovery path.
 */
@Singleton
class PlayServicesAccessTokenProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : GoogleAccessTokenProvider {
        override suspend fun accessToken(): String? {
            if (!GoogleServicesConfig.isConfigured) return null
            return try {
                val request =
                    AuthorizationRequest
                        .builder()
                        .setRequestedScopes(GoogleServicesConfig.requestedScopes.map(::Scope))
                        .build()
                val result = Identity.getAuthorizationClient(context).authorize(request).await()
                if (result.hasResolution()) null else result.accessToken
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
