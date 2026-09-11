package com.itsluminous.samaroh.feature.notes

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
 * Business/user context + permission gates for the Notes tab (ADR-077) — the exact
 * `ExpensesSession` shape: owners pass every gate; the signed-out/offline default is
 * owner-mode (true) so the app stays fully usable without a session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class NotesSession
    @Inject
    constructor(
        private val activeBusinessProvider: ActiveBusinessProvider,
        private val currentUserProvider: CurrentUserProvider,
        private val permissionGuard: PermissionGuard,
    ) {
        /** The business id all notes operations target (v1 single-business). */
        val businessIdFlow: Flow<String?> = activeBusinessProvider.activeBusiness.map { it?.id }

        suspend fun businessId(): String? = businessIdFlow.first()

        /** The acting user id for `created_by`/`updated_by`; owner fallback while signed out. */
        suspend fun userId(): String? =
            currentUserProvider.currentUserId.first()
                ?: activeBusinessProvider.activeBusiness.first()?.ownerUserId

        /** `notes.create` gate: the Create note / Create checklist buttons. */
        val canCreate: Flow<Boolean> = permissionGate { it.notes.create }

        /** `notes.edit` gate: edit, pin, complete/restore, trash/restore, tag changes. */
        val canEdit: Flow<Boolean> = permissionGate { it.notes.edit }

        /**
         * Normalized `notes.view_checklists` gate (absent inherits `view` — ADR-082):
         * whether checklists exist at all for this member (grid, search, tag counts,
         * the Create checklist button).
         */
        val canViewChecklists: Flow<Boolean> = permissionGate { it.notes.viewChecklistsEffective }

        /**
         * Normalized `notes.toggle_checklist` gate (absent inherits `edit` — ADR-082):
         * ticking/unticking checklist items inline (cards + the view popup).
         */
        val canToggleChecklist: Flow<Boolean> = permissionGate { it.notes.toggleChecklistEffective }

        /** `notes.delete` gate: delete forever from Trash + the 30-day purge sweep. */
        val canDelete: Flow<Boolean> = permissionGate { it.notes.delete }

        suspend fun canDeleteNow(): Boolean = canDelete.first()

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
