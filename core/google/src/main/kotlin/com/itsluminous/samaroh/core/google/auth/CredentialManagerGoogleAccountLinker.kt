package com.itsluminous.samaroh.core.google.auth

import android.content.Context
import android.content.Intent
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.itsluminous.samaroh.core.auth.SessionHolder
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.dao.GoogleAccountLinkDao
import com.itsluminous.samaroh.core.database.entity.GoogleAccountLinkEntity
import com.itsluminous.samaroh.core.google.GoogleServicesConfig
import com.itsluminous.samaroh.core.model.GoogleAccountLink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [GoogleAccountLinker] backed by Credential Manager (account pick, Google ID) plus the
 * Play services [Identity] authorization client for the incremental `drive.file` +
 * `calendar.events` scopes. Persists the non-secret link row in `google_accounts`
 * (ADR-003) and enqueues an outbox upsert so the link syncs like any other row.
 */
@Singleton
class CredentialManagerGoogleAccountLinker
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val sessionHolder: SessionHolder,
        private val linkDao: GoogleAccountLinkDao,
        private val outboxWriter: OutboxWriter,
        private val clock: Clock,
    ) : GoogleAccountLinker {
        private val json = Json { encodeDefaults = true }

        /** Link attempt awaiting the user's consent-sheet result. */
        private data class PendingLink(
            val userId: String,
            val email: String,
        )

        private var pendingLink: PendingLink? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        override val linkState: Flow<GoogleLinkState> =
            if (!GoogleServicesConfig.isConfigured) {
                flowOf(GoogleLinkState.NotConfigured)
            } else {
                sessionHolder.session.flatMapLatest { session ->
                    if (session == null) {
                        flowOf(GoogleLinkState.NotLinked)
                    } else {
                        linkDao.linkForUser(session.userId).map { link ->
                            if (link == null) {
                                GoogleLinkState.NotLinked
                            } else {
                                GoogleLinkState.Linked(email = link.email, grantedScopes = link.scopes)
                            }
                        }
                    }
                }
            }

        override suspend fun link(activityContext: Context): Result<GoogleLinkState.Linked> {
            if (!GoogleServicesConfig.isConfigured) return Result.failure(GoogleLinkException.NotConfigured())
            val session = sessionHolder.session.first() ?: return Result.failure(GoogleLinkException.NotSignedIn())

            val email =
                try {
                    pickAccount(activityContext)
                } catch (e: GetCredentialCancellationException) {
                    return Result.failure(GoogleLinkException.Cancelled())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Result.failure(GoogleLinkException.Failed(e))
                }

            return try {
                val authResult =
                    Identity
                        .getAuthorizationClient(activityContext)
                        .authorize(scopeAuthorizationRequest())
                        .await()
                val resolution = authResult.pendingIntent
                if (authResult.hasResolution() && resolution != null) {
                    pendingLink = PendingLink(userId = session.userId, email = email)
                    Result.failure(GoogleLinkException.NeedsScopeConsent(resolution))
                } else {
                    Result.success(persistLink(session.userId, email, authResult.grantedScopes.orEmpty()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(GoogleLinkException.Failed(e))
            }
        }

        override suspend fun completeLink(resultIntent: Intent?): Result<GoogleLinkState.Linked> {
            val pending = pendingLink ?: return Result.failure(GoogleLinkException.Cancelled())
            return try {
                val authResult =
                    Identity
                        .getAuthorizationClient(appContext)
                        .getAuthorizationResultFromIntent(resultIntent)
                pendingLink = null
                Result.success(persistLink(pending.userId, pending.email, authResult.grantedScopes.orEmpty()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(GoogleLinkException.Failed(e))
            }
        }

        override suspend fun unlink() {
            val session = sessionHolder.session.first() ?: return
            linkDao.unlink(session.userId)
            runCatching { CredentialManager.create(appContext).clearCredentialState(androidx.credentials.ClearCredentialStateRequest()) }
        }

        /** Shows the Credential Manager account picker and returns the chosen account's email. */
        private suspend fun pickAccount(activityContext: Context): String {
            val option =
                GetGoogleIdOption
                    .Builder()
                    .setServerClientId(GoogleServicesConfig.webClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val response = CredentialManager.create(activityContext).getCredential(activityContext, request)
            val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
            return credential.id
        }

        private fun scopeAuthorizationRequest(): AuthorizationRequest =
            AuthorizationRequest
                .builder()
                .setRequestedScopes(GoogleServicesConfig.requestedScopes.map(::Scope))
                .build()

        private suspend fun persistLink(
            userId: String,
            email: String,
            grantedScopes: List<String>,
        ): GoogleLinkState.Linked {
            val scopes = grantedScopes.ifEmpty { GoogleServicesConfig.requestedScopes }
            val existing = linkDao.linkForUser(userId).first()
            val now = clock.instant()
            val entity =
                GoogleAccountLinkEntity(
                    userId = userId,
                    email = email,
                    scopes = scopes,
                    // Preserve cached Drive/Calendar ids across relinks of the same account.
                    driveRootFolderId = existing?.driveRootFolderId.takeIf { existing?.email == email },
                    calendarId = existing?.calendarId.takeIf { existing?.email == email },
                    updatedAt = now,
                )
            linkDao.upsert(entity)
            outboxWriter.enqueue(
                entityType = "google_accounts",
                entityId = userId,
                operation = OutboxOperation.UPSERT,
                payloadJson =
                    json.encodeToString(
                        GoogleAccountLink.serializer(),
                        GoogleAccountLink(
                            userId = userId,
                            email = email,
                            scopes = scopes,
                            driveRootFolderId = entity.driveRootFolderId,
                            calendarId = entity.calendarId,
                            updatedAt = now,
                        ),
                    ),
            )
            return GoogleLinkState.Linked(email = email, grantedScopes = scopes)
        }
    }
