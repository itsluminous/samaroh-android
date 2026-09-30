package com.itsluminous.samaroh.feature.files

import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.model.MemberPermissions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Business/user context + permission gates for the Files tab (ADR-085) — the
 * `NotesSession` shape: owners pass every gate; the signed-out/offline default is
 * owner-mode (true) so the app stays usable without a session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FilesSession
    @Inject
    constructor(
        private val activeBusinessProvider: ActiveBusinessProvider,
        private val currentUserProvider: CurrentUserProvider,
        private val permissionGuard: PermissionGuard,
    ) {
        val businessIdFlow: Flow<String?> = activeBusinessProvider.activeBusiness.map { it?.id }

        suspend fun businessId(): String? = businessIdFlow.first()

        /** Display name of the active business — the Drive mirror's `Samaroh/{Business}/files/…` segment. */
        suspend fun businessName(): String? = activeBusinessProvider.activeBusiness.first()?.name

        /** Signed-in user id (null while signed out) — drives the "own upload" half of the file rename/move gate (ADR-090). */
        val userIdFlow: Flow<String?> = currentUserProvider.currentUserId

        /** The acting user id for `created_by`/`updated_by`; owner fallback while signed out. */
        suspend fun userId(): String? =
            currentUserProvider.currentUserId.first()
                ?: activeBusinessProvider.activeBusiness.first()?.ownerUserId

        /** `files.upload` gate: the Upload FAB and the share-sheet Save to Files row. */
        val canUpload: Flow<Boolean> = permissionGate { it.files.upload }

        /** Normalized `files.manage_folders` (absent inherits `upload`): New folder / Rename. */
        val canManageFolders: Flow<Boolean> = permissionGate { it.files.manageFoldersEffective }

        /** `files.delete` gate: delete file / delete folder. */
        val canDelete: Flow<Boolean> = permissionGate { it.files.delete }

        /** Owner-only: restrict a folder / edit its allow-list (design D6). */
        val isOwner: Flow<Boolean> =
            combine(currentUserProvider.currentUserId, activeBusinessProvider.activeBusiness) { userId, business ->
                userId to business
            }.flatMapLatest { (userId, business) ->
                when {
                    userId == null || business == null -> flowOf(true)
                    else -> permissionGuard.isOwner(business.id)
                }
            }

        private fun permissionGate(allowed: (MemberPermissions) -> Boolean): Flow<Boolean> =
            combine(
                currentUserProvider.currentUserId,
                activeBusinessProvider.activeBusiness,
            ) { userId, business ->
                userId to business
            }.flatMapLatest { (userId, business) ->
                when {
                    userId == null || business == null -> flowOf(true)
                    else ->
                        combine(
                            permissionGuard.permissions(business.id),
                            permissionGuard.isOwner(business.id),
                        ) { permissions, isOwner -> isOwner || allowed(permissions) }
                }
            }
    }
