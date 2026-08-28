package com.itsluminous.samaroh.feature.inventory

import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.model.MemberPermissions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Permission context for the Inventory tab, resolved from the app-wide session seam
 * (`ActiveBusinessProvider` + `CurrentUserProvider` + `PermissionGuard`) — the same
 * pattern as the Expenses tab's session (ADR-028 precedent).
 *
 * Signed-out/offline default (§3, current behavior): owner-mode — edits allowed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class InventorySession
    @Inject
    constructor(
        private val activeBusinessProvider: ActiveBusinessProvider,
        private val currentUserProvider: CurrentUserProvider,
        private val permissionGuard: PermissionGuard,
    ) {
        /**
         * Master-item edit/delete gate (§4.3): `inventory.manage_master_items` OR
         * `inventory.edit`. Drives the Masterlist CRUD affordances and the item-detail
         * screen's edit/delete menu.
         */
        val canManageMasterItems: Flow<Boolean> =
            permissionGate { it.inventory.manageMasterItems || it.inventory.edit }

        /**
         * Transaction-recording gate (§4.3): `inventory.create`. Drives the stock
         * screen's record-transaction FAB and the item detail's Add/Remove buttons.
         */
        val canRecordTransactions: Flow<Boolean> =
            permissionGate { it.inventory.create }

        /** Owners always pass [allowed]; signed-out/offline keeps the owner-mode default (true). */
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
