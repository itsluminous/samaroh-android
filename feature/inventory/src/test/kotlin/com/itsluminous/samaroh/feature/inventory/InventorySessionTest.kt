package com.itsluminous.samaroh.feature.inventory

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.InventoryPermissions
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** `inventory.manage_master_items`/`inventory.edit` gate mapping (§4.3 item CRUD). */
class InventorySessionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun session(
        userId: String?,
        guard: FakePermissionGuard,
    ) = InventorySession(FakeActiveBusinessProvider(), FakeCurrentUserProvider(userId), guard)

    @Test
    fun `signed out keeps the owner-mode default`() =
        runTest {
            val session = session(userId = null, guard = FakePermissionGuard())
            assertThat(session.canManageMasterItems.first()).isTrue()
        }

    @Test
    fun `owner passes with no explicit permissions`() =
        runTest {
            val guard = FakePermissionGuard()
            guard.ownerFlow.value = true
            assertThat(session("user-1", guard).canManageMasterItems.first()).isTrue()
        }

    @Test
    fun `member with manage_master_items passes`() =
        runTest {
            val guard = FakePermissionGuard()
            guard.permissionsFlow.value =
                MemberPermissions(inventory = InventoryPermissions(view = true, manageMasterItems = true))
            assertThat(session("user-1", guard).canManageMasterItems.first()).isTrue()
        }

    @Test
    fun `member with inventory edit passes`() =
        runTest {
            val guard = FakePermissionGuard()
            guard.permissionsFlow.value =
                MemberPermissions(inventory = InventoryPermissions(view = true, edit = true))
            assertThat(session("user-1", guard).canManageMasterItems.first()).isTrue()
        }

    @Test
    fun `viewer member is denied`() =
        runTest {
            val guard = FakePermissionGuard()
            guard.permissionsFlow.value = MemberPermissions(inventory = InventoryPermissions(view = true))
            assertThat(session("user-1", guard).canManageMasterItems.first()).isFalse()
        }

    // ---- canRecordTransactions (`inventory.create`, §4.3 FAB + Add/Remove) ----

    @Test
    fun `record transactions - signed out keeps the owner-mode default`() =
        runTest {
            val session = session(userId = null, guard = FakePermissionGuard())
            assertThat(session.canRecordTransactions.first()).isTrue()
        }

    @Test
    fun `record transactions - owner passes with no explicit permissions`() =
        runTest {
            val guard = FakePermissionGuard()
            guard.ownerFlow.value = true
            assertThat(session("user-1", guard).canRecordTransactions.first()).isTrue()
        }

    @Test
    fun `record transactions - member with inventory create passes`() =
        runTest {
            val guard = FakePermissionGuard()
            guard.permissionsFlow.value =
                MemberPermissions(inventory = InventoryPermissions(view = true, create = true))
            assertThat(session("user-1", guard).canRecordTransactions.first()).isTrue()
        }

    @Test
    fun `record transactions - viewer member is denied`() =
        runTest {
            val guard = FakePermissionGuard()
            guard.permissionsFlow.value = MemberPermissions(inventory = InventoryPermissions(view = true))
            assertThat(session("user-1", guard).canRecordTransactions.first()).isFalse()
        }

    @Test
    fun `record transactions - edit alone does not grant create`() =
        runTest {
            val guard = FakePermissionGuard()
            guard.permissionsFlow.value =
                MemberPermissions(inventory = InventoryPermissions(view = true, edit = true))
            assertThat(session("user-1", guard).canRecordTransactions.first()).isFalse()
        }
}

// ---- canViewAmounts (`inventory.view_amounts`, ADR-039 masking) appended tests ----

/** Per-session `canViewAmounts` mapping (ADR-039): absent = true; explicit false masks. */
class InventorySessionViewAmountsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun session(
        userId: String?,
        guard: FakePermissionGuard,
    ) = InventorySession(FakeActiveBusinessProvider(), FakeCurrentUserProvider(userId), guard)

    @Test
    fun `signed out keeps the owner-mode default (amounts visible)`() =
        runTest {
            assertThat(session(userId = null, guard = FakePermissionGuard()).canViewAmounts.first()).isTrue()
        }

    @Test
    fun `owner always sees amounts`() =
        runTest {
            val guard = FakePermissionGuard()
            guard.ownerFlow.value = true
            assertThat(session("user-1", guard).canViewAmounts.first()).isTrue()
        }

    @Test
    fun `member without explicit view_amounts sees amounts (absent = true)`() =
        runTest {
            val guard = FakePermissionGuard()
            guard.permissionsFlow.value = MemberPermissions(inventory = InventoryPermissions(view = true))
            assertThat(session("user-1", guard).canViewAmounts.first()).isTrue()
        }

    @Test
    fun `member with view_amounts off gets masked`() =
        runTest {
            val guard = FakePermissionGuard()
            guard.permissionsFlow.value =
                MemberPermissions(inventory = InventoryPermissions(view = true, viewAmounts = false))
            assertThat(session("user-1", guard).canViewAmounts.first()).isFalse()
        }
}
