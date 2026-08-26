package com.itsluminous.samaroh.feature.menu.ui.home

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.auth.Session
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.menu.data.CurrentBusinessProvider
import com.itsluminous.samaroh.feature.menu.fakes.FakeActiveBusinessProvider
import com.itsluminous.samaroh.feature.menu.fakes.FakeBusinessRepository
import com.itsluminous.samaroh.feature.menu.fakes.FakePermissionGuard
import com.itsluminous.samaroh.feature.menu.fakes.FakeSessionHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MenuHomeViewModelTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    private val businessRepository = FakeBusinessRepository(initialBusinesses = listOf(Fixtures.business()))
    private val permissionGuard = FakePermissionGuard()
    private val sessionHolder = FakeSessionHolder()

    private fun viewModel(): MenuHomeViewModel =
        MenuHomeViewModel(
            currentBusinessProvider = CurrentBusinessProvider(FakeActiveBusinessProvider(businessRepository)),
            permissionGuard = permissionGuard,
            sessionHolder = sessionHolder,
        )

    private fun TestScope.collecting(viewModel: MenuHomeViewModel): Job = launch { viewModel.uiState.collect {} }.also { runCurrent() }

    @Test
    fun `signed-in session exposes its email`() =
        runTest {
            sessionHolder.sessionFlow.value = Session(userId = "user-1", email = "owner@example.com")
            val viewModel = viewModel()
            val job = collecting(viewModel)

            assertThat(viewModel.uiState.value.signedInEmail).isEqualTo("owner@example.com")
            job.cancel()
        }

    @Test
    fun `no session (offline mode) exposes null email`() =
        runTest {
            val viewModel = viewModel()
            val job = collecting(viewModel)

            assertThat(viewModel.uiState.value.signedInEmail).isNull()
            job.cancel()
        }

    @Test
    fun `sign-out clears the email`() =
        runTest {
            sessionHolder.sessionFlow.value = Session(userId = "user-1", email = "owner@example.com")
            val viewModel = viewModel()
            val job = collecting(viewModel)
            assertThat(viewModel.uiState.value.signedInEmail).isEqualTo("owner@example.com")

            sessionHolder.signOut()
            runCurrent()

            assertThat(viewModel.uiState.value.signedInEmail).isNull()
            job.cancel()
        }

    @Test
    fun `owner flag combines with session state`() =
        runTest {
            permissionGuard.ownerFlow.value = true
            sessionHolder.sessionFlow.value = Session(userId = "user-1", email = "owner@example.com")
            val viewModel = viewModel()
            val job = collecting(viewModel)

            assertThat(viewModel.uiState.value.isOwner).isTrue()
            assertThat(viewModel.uiState.value.signedInEmail).isEqualTo("owner@example.com")
            job.cancel()
        }
}
