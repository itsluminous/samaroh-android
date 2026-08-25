package com.itsluminous.samaroh.feature.reports.ui.home

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.ReportsPermissions
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.reports.fakes.FakeActiveBusinessProvider
import com.itsluminous.samaroh.feature.reports.fakes.FakePermissionGuard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsHomeViewModelTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    @Test
    fun `member with reports view can see the report list`() =
        runTest(dispatcherRule.dispatcher) {
            val guard = FakePermissionGuard(MemberPermissions(reports = ReportsPermissions(view = true)))
            val viewModel = ReportsHomeViewModel(FakeActiveBusinessProvider(Fixtures.business()), guard)

            viewModel.uiState.test {
                var state = awaitItem()
                while (state.loading) state = awaitItem()
                assertThat(state.canView).isTrue()
            }
        }

    @Test
    fun `member without the permission is blocked, and no business means no access`() =
        runTest(dispatcherRule.dispatcher) {
            val denied = ReportsHomeViewModel(FakeActiveBusinessProvider(Fixtures.business()), FakePermissionGuard(MemberPermissions()))
            denied.uiState.test {
                var state = awaitItem()
                while (state.loading) state = awaitItem()
                assertThat(state.canView).isFalse()
            }

            val noBusiness = ReportsHomeViewModel(FakeActiveBusinessProvider(null), FakePermissionGuard())
            noBusiness.uiState.test {
                var state = awaitItem()
                while (state.loading) state = awaitItem()
                assertThat(state.canView).isFalse()
            }
        }
}
