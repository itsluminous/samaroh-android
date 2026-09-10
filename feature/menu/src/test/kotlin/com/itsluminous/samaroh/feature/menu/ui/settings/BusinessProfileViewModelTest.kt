package com.itsluminous.samaroh.feature.menu.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.SettingsPermissions
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.menu.fakes.FakeActiveBusinessProvider
import com.itsluminous.samaroh.feature.menu.fakes.FakeBusinessRepository
import com.itsluminous.samaroh.feature.menu.fakes.FakePermissionGuard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 * Business profile editor gating (ADR-080): editing requires owner or
 * `settings.manage_business` — anyone else gets the read-only view and the ViewModel
 * refuses the write outright (defence in depth under the authoritative RLS layer).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BusinessProfileViewModelTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Clock.fixed(Instant.parse("2026-09-10T09:00:00Z"), ZoneOffset.UTC)

    private lateinit var businessRepository: FakeBusinessRepository
    private lateinit var permissionGuard: FakePermissionGuard
    private lateinit var viewModel: BusinessProfileViewModel

    @Before
    fun setUp() {
        businessRepository = FakeBusinessRepository(initialBusinesses = listOf(Fixtures.business()))
        permissionGuard = FakePermissionGuard()
        viewModel =
            BusinessProfileViewModel(
                appContext = context,
                activeBusinessProvider = FakeActiveBusinessProvider(businessRepository),
                businessRepository = businessRepository,
                permissionGuard = permissionGuard,
                clock = clock,
            )
    }

    @Test
    fun `canEdit is true for the owner`() =
        runTest(dispatcherRule.dispatcher) {
            val collector = launch { viewModel.canEdit.collect {} }
            permissionGuard.ownerFlow.value = true
            runCurrent()

            assertThat(viewModel.canEdit.value).isTrue()
            collector.cancel()
        }

    @Test
    fun `canEdit is true with the manage_business permission`() =
        runTest(dispatcherRule.dispatcher) {
            val collector = launch { viewModel.canEdit.collect {} }
            permissionGuard.permissionsFlow.value =
                MemberPermissions(settings = SettingsPermissions(manageBusiness = true))
            runCurrent()

            assertThat(viewModel.canEdit.value).isTrue()
            collector.cancel()
        }

    @Test
    fun `canEdit is false for a viewer without manage_business`() =
        runTest(dispatcherRule.dispatcher) {
            val collector = launch { viewModel.canEdit.collect {} }
            runCurrent()

            assertThat(viewModel.canEdit.value).isFalse()
            collector.cancel()
        }

    @Test
    fun `save is a no-op for a member without manage_business`() =
        runTest(dispatcherRule.dispatcher) {
            // The RLS-violation repro: the viewer's local save must be refused up front
            // instead of "succeeding" locally and dying on the push (ADR-080).
            val collector = launch { viewModel.canEdit.collect {} }
            val businessCollector = launch { viewModel.business.collect {} }
            runCurrent()
            val original = businessRepository.businesses().first().single()

            viewModel.save(
                name = "Hijacked name",
                businessType = original.businessType,
                address = original.address.orEmpty(),
                ownerName = original.ownerName,
                invoicePrefix = original.invoicePrefix,
            )
            runCurrent()

            assertThat(businessRepository.businesses().first().single()).isEqualTo(original)
            assertThat(viewModel.message.value).isNull()
            collector.cancel()
            businessCollector.cancel()
        }

    @Test
    fun `save persists for a member with manage_business`() =
        runTest(dispatcherRule.dispatcher) {
            val collector = launch { viewModel.canEdit.collect {} }
            val businessCollector = launch { viewModel.business.collect {} }
            permissionGuard.permissionsFlow.value =
                MemberPermissions(settings = SettingsPermissions(manageBusiness = true))
            runCurrent()
            val original = businessRepository.businesses().first().single()

            viewModel.save(
                name = "Renamed venue",
                businessType = original.businessType,
                address = original.address.orEmpty(),
                ownerName = original.ownerName,
                invoicePrefix = "RV",
            )
            runCurrent()

            val saved = businessRepository.businesses().first().single()
            assertThat(saved.name).isEqualTo("Renamed venue")
            assertThat(saved.invoicePrefix).isEqualTo("RV")
            assertThat(saved.updatedAt).isEqualTo(clock.instant())
            collector.cancel()
            businessCollector.cancel()
        }

    @Test
    fun `save persists for the owner`() =
        runTest(dispatcherRule.dispatcher) {
            val collector = launch { viewModel.canEdit.collect {} }
            val businessCollector = launch { viewModel.business.collect {} }
            permissionGuard.ownerFlow.value = true
            runCurrent()
            val original = businessRepository.businesses().first().single()

            viewModel.save(
                name = "Owner rename",
                businessType = original.businessType,
                address = original.address.orEmpty(),
                ownerName = original.ownerName,
                invoicePrefix = original.invoicePrefix,
            )
            runCurrent()

            assertThat(
                businessRepository
                    .businesses()
                    .first()
                    .single()
                    .name,
            ).isEqualTo("Owner rename")
            collector.cancel()
            businessCollector.cancel()
        }
}
