package com.itsluminous.samaroh.feature.expenses

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
 * Business/user context for the Expenses tab, resolved from the app-wide session seam
 * (`ActiveBusinessProvider` + `CurrentUserProvider`, docs/decisions.md ADR-017). Replaces
 * the Wave-1 `ExpensesSessionDefaults` placeholder constants.
 *
 * Signed-out/offline default (§3, current behavior): owner-mode on the first local
 * business — edits allowed, `createdBy` falls back to the business owner. The fixture ids
 * below remain only as last-resort fallbacks for a pre-onboarding empty database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ExpensesSession
    @Inject
    constructor(
        private val activeBusinessProvider: ActiveBusinessProvider,
        private val currentUserProvider: CurrentUserProvider,
        private val permissionGuard: PermissionGuard,
    ) {
        /** The business id all expenses operations target (v1 single-business). */
        val businessIdFlow: Flow<String> =
            activeBusinessProvider.activeBusiness.map { it?.id ?: FIXTURE_BUSINESS_ID }

        /** Active business display name — the add/edit-party "Associated with {business}?" pill title. */
        val businessName: Flow<String> =
            activeBusinessProvider.activeBusiness.map { it?.name.orEmpty() }

        suspend fun businessId(): String = businessIdFlow.first()

        /** The acting user id for `created_by`; owner (then fixture) fallback while signed out. */
        suspend fun userId(): String =
            currentUserProvider.currentUserId.first()
                ?: activeBusinessProvider.activeBusiness.first()?.ownerUserId
                ?: FIXTURE_USER_ID

        /**
         * `expenses.edit` gate (§4.2). Owners always pass; signed-out/offline keeps the
         * historical owner-mode default (true).
         */
        val canEditEntries: Flow<Boolean> = permissionGate { it.expenses.edit }

        /** Party edit gate (ADR-028): `expenses.edit` OR `expenses.manage_parties`. */
        val canManageParties: Flow<Boolean> = permissionGate { it.expenses.edit || it.expenses.manageParties }

        /** Party/entry delete gate (ADR-028): `expenses.delete`. */
        val canDeleteParties: Flow<Boolean> = permissionGate { it.expenses.delete }

        /**
         * Owners always pass [allowed]; signed-out/offline keeps the historical
         * owner-mode default (true).
         */
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

        private companion object {
            /** Wave-0 fixture/demo ids — pre-onboarding fallback only. */
            const val FIXTURE_BUSINESS_ID = "00000000-0000-0000-0000-00000000b1a5"
            const val FIXTURE_USER_ID = "00000000-0000-0000-0000-0000000000fe"
        }
    }
