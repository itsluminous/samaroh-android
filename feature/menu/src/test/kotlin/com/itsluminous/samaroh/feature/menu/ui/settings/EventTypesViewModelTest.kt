package com.itsluminous.samaroh.feature.menu.ui.settings

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.color.BookingColor
import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.data.repository.EventTypeRepository
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.SettingsPermissions
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.menu.data.CurrentBusinessProvider
import com.itsluminous.samaroh.feature.menu.fakes.FakeActiveBusinessProvider
import com.itsluminous.samaroh.feature.menu.fakes.FakeBusinessRepository
import com.itsluminous.samaroh.feature.menu.fakes.FakePermissionGuard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Manage-screen view model (ADR-032): permission gating, add with duplicate-label
 * validation, edit, soft delete, and up/down reorder via sort_order swaps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EventTypesViewModelTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-08-27T10:00:00Z")

    private class FakeEventTypeRepository(
        initial: List<EventType>,
    ) : EventTypeRepository {
        val rows = MutableStateFlow(initial)

        override fun presets(businessId: String): Flow<List<EventType>> =
            rows.map { list ->
                list
                    .filter { it.businessId == businessId && it.deletedAt == null }
                    .sortedWith(compareBy({ it.sortOrder }, { it.label.lowercase() }))
            }

        override suspend fun presetsOnce(businessId: String): List<EventType> = presets(businessId).let { rows.value }

        override suspend fun preset(id: String): EventType? = rows.value.firstOrNull { it.id == id }

        override suspend fun savePreset(preset: EventType) {
            rows.value = rows.value.filterNot { it.id == preset.id } + preset
        }

        override suspend fun deletePreset(id: String) {
            rows.value = rows.value.map { if (it.id == id) it.copy(deletedAt = Instant.parse("2026-09-01T00:00:00Z")) else it }
        }

        override suspend fun labelInUse(
            businessId: String,
            label: String,
            excludingId: String?,
        ): Boolean =
            rows.value.any {
                it.businessId == businessId &&
                    it.deletedAt == null &&
                    it.id != excludingId &&
                    it.label.equals(label.trim(), ignoreCase = true)
            }

        override suspend fun seedDefaults(businessId: String) = Unit
    }

    private class RecordingSyncScheduler : SyncScheduler {
        var immediateSyncs = 0

        override fun requestImmediateSync() {
            immediateSyncs++
        }

        override fun ensurePeriodicSync() = Unit
    }

    private class FakeColors : BookingColorCatalog {
        override val colors: List<BookingColor> =
            listOf(BookingColor("tomato", "#C62828", "#FFFFFF", labelRes = 1))
    }

    private fun preset(
        label: String,
        sortOrder: Int,
        id: String = "et-$label",
        color: String? = null,
    ) = EventType(
        id = id,
        businessId = Fixtures.BUSINESS_ID,
        label = label,
        icon = "✨",
        color = color,
        sortOrder = sortOrder,
        createdAt = now,
        updatedAt = now,
    )

    private lateinit var repository: FakeEventTypeRepository
    private lateinit var permissionGuard: FakePermissionGuard
    private lateinit var syncScheduler: RecordingSyncScheduler
    private lateinit var viewModel: EventTypesViewModel

    @Before
    fun setUp() {
        repository =
            FakeEventTypeRepository(
                listOf(preset("Wedding", 0, color = "tomato"), preset("Birthday", 1), preset("Custom", 2)),
            )
        permissionGuard = FakePermissionGuard()
        syncScheduler = RecordingSyncScheduler()
        val businessRepository = FakeBusinessRepository(initialBusinesses = listOf(Fixtures.business()))
        viewModel =
            EventTypesViewModel(
                currentBusinessProvider = CurrentBusinessProvider(FakeActiveBusinessProvider(businessRepository)),
                eventTypeRepository = repository,
                permissionGuard = permissionGuard,
                bookingColorsProvider = FakeColors(),
                syncScheduler = syncScheduler,
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
    }

    private fun kotlinx.coroutines.test.TestScope.awaitState(): EventTypesUiState {
        val job = launch { viewModel.uiState.first { !it.loading } }
        runCurrent()
        job.cancel()
        return viewModel.uiState.value
    }

    @Test
    fun `members without the permission cannot manage`() =
        runTest {
            permissionGuard.ownerFlow.value = false
            permissionGuard.permissionsFlow.value = MemberPermissions()

            assertThat(awaitState().canManage).isFalse()
        }

    @Test
    fun `owner or settings manage_business can manage`() =
        runTest {
            permissionGuard.ownerFlow.value = true
            assertThat(awaitState().canManage).isTrue()

            permissionGuard.ownerFlow.value = false
            permissionGuard.permissionsFlow.value =
                MemberPermissions(settings = SettingsPermissions(manageBusiness = true))
            runCurrent()
            assertThat(awaitState().canManage).isTrue()
        }

    @Test
    fun `presets surface in sort order`() =
        runTest {
            permissionGuard.ownerFlow.value = true
            assertThat(awaitState().presets.map { it.label })
                .containsExactly("Wedding", "Birthday", "Custom")
                .inOrder()
        }

    @Test
    fun `saveDraft adds a preset at the end with the drafted colour`() =
        runTest {
            permissionGuard.ownerFlow.value = true
            awaitState()
            viewModel.startAdd()
            viewModel.setDraftLabel("Housewarming")
            viewModel.setDraftIcon("🏠")
            viewModel.setDraftColor("tomato")
            viewModel.saveDraft()
            runCurrent()

            val added = repository.rows.value.first { it.label == "Housewarming" }
            assertThat(added.icon).isEqualTo("🏠")
            assertThat(added.color).isEqualTo("tomato")
            assertThat(added.sortOrder).isEqualTo(3)
            assertThat(viewModel.draft.value).isNull()
            assertThat(syncScheduler.immediateSyncs).isEqualTo(1)
        }

    @Test
    fun `saveDraft flags duplicate labels case-insensitively and keeps the dialog open`() =
        runTest {
            permissionGuard.ownerFlow.value = true
            awaitState()
            viewModel.startAdd()
            viewModel.setDraftLabel("wedding")
            viewModel.saveDraft()
            runCurrent()

            assertThat(viewModel.draft.value?.duplicateLabel).isTrue()
            assertThat(repository.rows.value.count { it.deletedAt == null }).isEqualTo(3)
        }

    @Test
    fun `editing a preset keeps its identity and sort position`() =
        runTest {
            permissionGuard.ownerFlow.value = true
            awaitState()
            viewModel.startEdit(repository.rows.value.first { it.label == "Wedding" })
            viewModel.setDraftLabel("Shaadi")
            viewModel.saveDraft()
            runCurrent()

            val edited = repository.rows.value.first { it.id == "et-Wedding" }
            assertThat(edited.label).isEqualTo("Shaadi")
            assertThat(edited.sortOrder).isEqualTo(0)
        }

    @Test
    fun `confirmDelete soft-deletes the pending preset`() =
        runTest {
            permissionGuard.ownerFlow.value = true
            val target = awaitState().presets.first { it.label == "Birthday" }
            viewModel.requestDelete(target)
            viewModel.confirmDelete()
            runCurrent()

            assertThat(
                repository.rows.value
                    .first { it.id == target.id }
                    .deletedAt,
            ).isNotNull()
            assertThat(viewModel.pendingDelete.value).isNull()
        }

    @Test
    fun `move up swaps sort_order with the previous row`() =
        runTest {
            permissionGuard.ownerFlow.value = true
            val birthday = awaitState().presets.first { it.label == "Birthday" }
            viewModel.move(birthday, up = true)
            runCurrent()

            val labelsInOrder =
                repository.rows.value
                    .filter { it.deletedAt == null }
                    .sortedBy { it.sortOrder }
                    .map { it.label }
            assertThat(labelsInOrder).containsExactly("Birthday", "Wedding", "Custom").inOrder()
        }

    @Test
    fun `writes are ignored without the manage permission`() =
        runTest {
            permissionGuard.ownerFlow.value = false
            awaitState()
            viewModel.startAdd()
            viewModel.setDraftLabel("Sneaky")
            viewModel.saveDraft()
            val wedding = repository.rows.value.first { it.label == "Wedding" }
            viewModel.requestDelete(wedding)
            viewModel.confirmDelete()
            viewModel.move(wedding, up = false)
            runCurrent()

            assertThat(repository.rows.value.map { it.label }).containsExactly("Wedding", "Birthday", "Custom")
            assertThat(repository.rows.value.all { it.deletedAt == null }).isTrue()
        }
}
