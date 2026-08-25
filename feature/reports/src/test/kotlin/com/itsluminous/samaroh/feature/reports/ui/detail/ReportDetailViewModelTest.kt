package com.itsluminous.samaroh.feature.reports.ui.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.core.testing.MainDispatcherRule
import com.itsluminous.samaroh.feature.reports.domain.RangePreset
import com.itsluminous.samaroh.feature.reports.domain.ReportType
import com.itsluminous.samaroh.feature.reports.export.ReportExportFormat
import com.itsluminous.samaroh.feature.reports.export.ReportTable
import com.itsluminous.samaroh.feature.reports.fakes.FakeActiveBusinessProvider
import com.itsluminous.samaroh.feature.reports.fakes.FakeBookingRepository
import com.itsluminous.samaroh.feature.reports.fakes.FakeExpensesRepository
import com.itsluminous.samaroh.feature.reports.fakes.FakeInventoryOverviewRepository
import com.itsluminous.samaroh.feature.reports.fakes.FakePermissionGuard
import com.itsluminous.samaroh.feature.reports.fakes.FakeReportExporter
import com.itsluminous.samaroh.feature.reports.fakes.FakeReportsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ReportDetailViewModelTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    // Fixed "today": 2026-08-25 → the default 12-month window is 2025-09-01..2026-08-31.
    private val clock = Clock.fixed(Instant.parse("2026-08-25T09:00:00Z"), ZoneOffset.UTC)

    private val businessProvider = FakeActiveBusinessProvider(Fixtures.business())
    private val permissionGuard = FakePermissionGuard()
    private val bookingRepository = FakeBookingRepository()
    private val expensesRepository = FakeExpensesRepository()
    private val reportsRepository = FakeReportsRepository()
    private val inventoryRepository = FakeInventoryOverviewRepository()
    private val exporter = FakeReportExporter()

    private fun viewModel(type: ReportType): ReportDetailViewModel =
        ReportDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(ReportDetailViewModel.REPORT_TYPE_ARG to type.routeArg)),
            activeBusinessProvider = businessProvider,
            permissionGuard = permissionGuard,
            bookingRepository = bookingRepository,
            expensesRepository = expensesRepository,
            reportsRepository = reportsRepository,
            inventoryOverviewRepository = inventoryRepository,
            exporter = exporter,
            clock = clock,
        )

    @Test
    fun `revenue report aggregates bookings and payments into months`() =
        runTest(dispatcherRule.dispatcher) {
            val booking = Fixtures.booking(startDate = LocalDate.of(2026, 8, 10), totalAmountPaise = 2_00_000_00L)
            bookingRepository.bookingsFlow.value = listOf(booking)
            bookingRepository.paymentsFlow.value = listOf(Fixtures.payment(bookingId = booking.id, amountPaise = 50_000_00L))

            viewModel(ReportType.REVENUE).uiState.test {
                val state = awaitItemMatching { it.allowed && it.data != null }
                val data = state.data as ReportData.Revenue
                assertThat(data.months).hasSize(12)
                val august = data.months.first { it.month == YearMonth.of(2026, 8) }
                assertThat(august.collectedPaise).isEqualTo(50_000_00L)
                assertThat(august.outstandingPaise).isEqualTo(1_50_000_00L)
            }
        }

    @Test
    fun `member without reports view permission gets no data`() =
        runTest(dispatcherRule.dispatcher) {
            permissionGuard.permissionsFlow.value = MemberPermissions()
            bookingRepository.bookingsFlow.value = listOf(Fixtures.booking(startDate = LocalDate.of(2026, 8, 10)))

            viewModel(ReportType.REVENUE).uiState.test {
                val state = awaitItemMatching { !it.loading }
                assertThat(state.allowed).isFalse()
                assertThat(state.data).isNull()
            }
        }

    @Test
    fun `changing the range preset re-aggregates over the new window`() =
        runTest(dispatcherRule.dispatcher) {
            val old = Fixtures.booking(startDate = LocalDate.of(2026, 1, 10), totalAmountPaise = 1_00_000_00L)
            val recent = Fixtures.booking(startDate = LocalDate.of(2026, 8, 10), totalAmountPaise = 2_00_000_00L)
            bookingRepository.bookingsFlow.value = listOf(old, recent)

            val vm = viewModel(ReportType.REVENUE)
            vm.uiState.test {
                val yearState = awaitItemMatching { it.allowed && it.data != null }
                assertThat((yearState.data as ReportData.Revenue).months.sumOf { it.totalPaise }).isEqualTo(3_00_000_00L)

                vm.selectPreset(RangePreset.THIS_MONTH)

                val monthState = awaitItemMatching { it.preset == RangePreset.THIS_MONTH && it.data != null }
                val months = (monthState.data as ReportData.Revenue).months
                assertThat(months).hasSize(1)
                assertThat(months.single().totalPaise).isEqualTo(2_00_000_00L)
            }
        }

    @Test
    fun `custom range orders reversed dates`() =
        runTest(dispatcherRule.dispatcher) {
            val vm = viewModel(ReportType.REVENUE)
            vm.uiState.test {
                awaitItemMatching { it.allowed }

                vm.selectCustomRange(start = LocalDate.of(2026, 8, 20), end = LocalDate.of(2026, 8, 1))

                val state = awaitItemMatching { it.preset == RangePreset.CUSTOM }
                assertThat(state.range.start).isEqualTo(LocalDate.of(2026, 8, 1))
                assertThat(state.range.end).isEqualTo(LocalDate.of(2026, 8, 20))
            }
        }

    @Test
    fun `inventory valuation flows through the overview repository`() =
        runTest(dispatcherRule.dispatcher) {
            inventoryRepository.linesFlow.value =
                listOf(
                    com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine(
                        masterItemId = "item",
                        name = "fixture-item",
                        unit = "pcs",
                        imagePath = null,
                        currentQuantity = 7.0,
                        totalValuePaise = 700_00L,
                        lastTransactionAt = Fixtures.NOW,
                    ),
                )

            viewModel(ReportType.INVENTORY_VALUATION).uiState.test {
                val state = awaitItemMatching { it.allowed && it.data != null }
                val data = state.data as ReportData.Inventory
                assertThat(data.rows.single().valuePaise).isEqualTo(700_00L)
            }
        }

    @Test
    fun `successful export surfaces a one-shot share request`() =
        runTest(dispatcherRule.dispatcher) {
            val vm = viewModel(ReportType.REVENUE)
            val table = ReportTable(title = "t", subtitle = "s", columns = listOf("c"), rows = emptyList())

            vm.export(table, ReportExportFormat.CSV)

            vm.shareRequests.test {
                val exported = awaitItemMatching { it != null }
                assertThat(exported!!.absolutePath).contains("revenue")
                vm.onShared()
                assertThat(awaitItemMatching { it == null }).isNull()
            }
            assertThat(exporter.lastFormat).isEqualTo(ReportExportFormat.CSV)
        }

    @Test
    fun `failed export raises the failure flag until consumed`() =
        runTest(dispatcherRule.dispatcher) {
            exporter.failNext = true
            val vm = viewModel(ReportType.PROFIT)
            val table = ReportTable(title = "t", subtitle = "s", columns = listOf("c"), rows = emptyList())

            vm.export(table, ReportExportFormat.PDF)

            vm.exportFailed.test {
                assertThat(awaitItemMatching { it }).isTrue()
                vm.onExportFailureShown()
                assertThat(awaitItemMatching { !it }).isFalse()
            }
        }
}

/** Skips intermediate emissions until one matches, then returns it. */
private suspend fun <T> app.cash.turbine.TurbineTestContext<T>.awaitItemMatching(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
