package com.itsluminous.samaroh.feature.reports.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.repository.InventoryOverviewRepository
import com.itsluminous.samaroh.core.data.repository.ReportsRepository
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.feature.reports.domain.AgingBucket
import com.itsluminous.samaroh.feature.reports.domain.AgingEntry
import com.itsluminous.samaroh.feature.reports.domain.BookingSourceBreakdownCalculator
import com.itsluminous.samaroh.feature.reports.domain.CollectionEfficiencyCalculator
import com.itsluminous.samaroh.feature.reports.domain.CollectionResult
import com.itsluminous.samaroh.feature.reports.domain.DateRanges
import com.itsluminous.samaroh.feature.reports.domain.DuesAgingCalculator
import com.itsluminous.samaroh.feature.reports.domain.EventTypeBreakdownCalculator
import com.itsluminous.samaroh.feature.reports.domain.EventTypeRow
import com.itsluminous.samaroh.feature.reports.domain.ExpenseMonth
import com.itsluminous.samaroh.feature.reports.domain.ExpenseSummaryCalculator
import com.itsluminous.samaroh.feature.reports.domain.InventoryValuationCalculator
import com.itsluminous.samaroh.feature.reports.domain.OccupancyCalculator
import com.itsluminous.samaroh.feature.reports.domain.OccupancyMonth
import com.itsluminous.samaroh.feature.reports.domain.PartyExpenseRow
import com.itsluminous.samaroh.feature.reports.domain.PersonalExpenseRow
import com.itsluminous.samaroh.feature.reports.domain.PersonalExpensesCalculator
import com.itsluminous.samaroh.feature.reports.domain.ProfitCalculator
import com.itsluminous.samaroh.feature.reports.domain.ProfitMonth
import com.itsluminous.samaroh.feature.reports.domain.RangePreset
import com.itsluminous.samaroh.feature.reports.domain.ReportDateRange
import com.itsluminous.samaroh.feature.reports.domain.ReportType
import com.itsluminous.samaroh.feature.reports.domain.RevenueMonth
import com.itsluminous.samaroh.feature.reports.domain.RevenueSummaryCalculator
import com.itsluminous.samaroh.feature.reports.domain.SourceRow
import com.itsluminous.samaroh.feature.reports.domain.ValuationRow
import com.itsluminous.samaroh.feature.reports.export.ExportedReport
import com.itsluminous.samaroh.feature.reports.export.ReportExportFormat
import com.itsluminous.samaroh.feature.reports.export.ReportExporter
import com.itsluminous.samaroh.feature.reports.export.ReportTable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** Aggregated, chart-ready result of one report over the active date range. */
sealed interface ReportData {
    val isEmpty: Boolean

    data class Revenue(
        val months: List<RevenueMonth>,
    ) : ReportData {
        override val isEmpty: Boolean get() = months.all { it.totalPaise == 0L }
    }

    data class Aging(
        val entries: List<AgingEntry>,
        val bucketTotals: Map<AgingBucket, Long>,
    ) : ReportData {
        override val isEmpty: Boolean get() = entries.isEmpty()
    }

    data class Occupancy(
        val months: List<OccupancyMonth>,
    ) : ReportData {
        override val isEmpty: Boolean get() = months.all { it.bookedDays == 0 }
    }

    data class EventTypes(
        val rows: List<EventTypeRow>,
    ) : ReportData {
        override val isEmpty: Boolean get() = rows.isEmpty()
    }

    data class Sources(
        val rows: List<SourceRow>,
    ) : ReportData {
        override val isEmpty: Boolean get() = rows.isEmpty()
    }

    data class Expenses(
        val months: List<ExpenseMonth>,
        val rows: List<PartyExpenseRow>,
    ) : ReportData {
        override val isEmpty: Boolean get() = months.all { it.totalPaise == 0L } && rows.isEmpty()

        val top10: List<PartyExpenseRow> get() = ExpenseSummaryCalculator.top(rows)
    }

    data class Profit(
        val months: List<ProfitMonth>,
    ) : ReportData {
        override val isEmpty: Boolean get() = months.all { it.incomePaise == 0L && it.expensePaise == 0L }
    }

    data class Inventory(
        val rows: List<ValuationRow>,
    ) : ReportData {
        override val isEmpty: Boolean get() = rows.isEmpty()
    }

    data class Collection(
        val result: CollectionResult,
    ) : ReportData {
        override val isEmpty: Boolean get() = result.entries.isEmpty()
    }

    /** ADR-027: monthly net spend per personal (non-business-related) party. */
    data class PersonalExpenses(
        val rows: List<PersonalExpenseRow>,
    ) : ReportData {
        override val isEmpty: Boolean get() = rows.isEmpty()
    }
}

data class ReportDetailUiState(
    val type: ReportType,
    val loading: Boolean = true,
    val allowed: Boolean = false,
    val preset: RangePreset = RangePreset.LAST_12_MONTHS,
    val range: ReportDateRange,
    val data: ReportData? = null,
)

@HiltViewModel
class ReportDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        activeBusinessProvider: ActiveBusinessProvider,
        currentUserProvider: CurrentUserProvider,
        permissionGuard: PermissionGuard,
        private val bookingRepository: BookingRepository,
        private val expensesRepository: ExpensesRepository,
        private val reportsRepository: ReportsRepository,
        private val inventoryOverviewRepository: InventoryOverviewRepository,
        private val exporter: ReportExporter,
        private val clock: Clock,
    ) : ViewModel() {
        val type: ReportType = ReportType.fromRoute(savedStateHandle[REPORT_TYPE_ARG])

        private val today: LocalDate get() = LocalDate.now(clock)

        private data class RangeSelection(
            val preset: RangePreset,
            val range: ReportDateRange,
        )

        private val rangeSelection =
            MutableStateFlow(
                RangeSelection(RangePreset.LAST_12_MONTHS, DateRanges.forPreset(RangePreset.LAST_12_MONTHS, LocalDate.now(clock))),
            )

        /** Path+mime of a finished export, consumed once by the screen to open the share sheet. */
        private val shareRequest = MutableStateFlow<ExportedReport?>(null)
        val shareRequests: StateFlow<ExportedReport?> = shareRequest.asStateFlow()

        /** True after a failed export, consumed once by the screen to show a snackbar. */
        private val exportFailedState = MutableStateFlow(false)
        val exportFailed: StateFlow<Boolean> = exportFailedState.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<ReportDetailUiState> =
            combine(
                activeBusinessProvider.activeBusiness,
                currentUserProvider.currentUserId,
                rangeSelection,
            ) { business, userId, selection -> Triple(business, userId, selection) }
                .flatMapLatest { (business, userId, selection) ->
                    when {
                        business == null ->
                            flowOf(baseState(selection).copy(loading = false, allowed = false))
                        // Signed-out/offline: owner-mode default on the local business (ADR-017, §3).
                        userId == null ->
                            dataFlow(business.id, selection.range).map { data ->
                                baseState(selection).copy(loading = false, allowed = true, data = data)
                            }
                        else ->
                            permissionGuard.permissions(business.id).flatMapLatest { permissions ->
                                // ADR-039: money reports additionally need reports.view_amounts.
                                if (!permissions.reports.view || (type.requiresAmounts && !permissions.reports.viewAmounts)) {
                                    flowOf(baseState(selection).copy(loading = false, allowed = false))
                                } else {
                                    dataFlow(business.id, selection.range).map { data ->
                                        baseState(selection).copy(loading = false, allowed = true, data = data)
                                    }
                                }
                            }
                    }
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5_000),
                    baseState(rangeSelection.value),
                )

        private fun baseState(selection: RangeSelection): ReportDetailUiState =
            ReportDetailUiState(type = type, preset = selection.preset, range = selection.range)

        fun selectPreset(preset: RangePreset) {
            if (preset == RangePreset.CUSTOM) return // custom needs explicit dates via selectCustomRange
            rangeSelection.value = RangeSelection(preset, DateRanges.forPreset(preset, today))
        }

        fun selectCustomRange(
            start: LocalDate,
            end: LocalDate,
        ) {
            val ordered = if (end.isBefore(start)) ReportDateRange(end, start) else ReportDateRange(start, end)
            rangeSelection.value = RangeSelection(RangePreset.CUSTOM, ordered)
        }

        /** Writes the (already localized) table to a file, then hands it to the screen to share. */
        fun export(
            table: ReportTable,
            format: ReportExportFormat,
        ) {
            viewModelScope.launch {
                exporter
                    .export(fileBaseName = type.routeArg, table = table, format = format)
                    .onSuccess { shareRequest.value = it }
                    .onFailure { exportFailedState.value = true }
            }
        }

        fun onShared() {
            shareRequest.value = null
        }

        fun onExportFailureShown() {
            exportFailedState.value = false
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun dataFlow(
            businessId: String,
            range: ReportDateRange,
        ): Flow<ReportData> =
            when (type) {
                ReportType.REVENUE ->
                    bookingsWithPayments(businessId, range) { bookings, payments ->
                        ReportData.Revenue(RevenueSummaryCalculator.calculate(bookings, payments, range))
                    }
                ReportType.DUES_AGING ->
                    bookingsWithPayments(businessId, range) { bookings, payments ->
                        val entries = DuesAgingCalculator.calculate(bookings, payments, today)
                        ReportData.Aging(entries, DuesAgingCalculator.bucketTotals(entries))
                    }
                ReportType.OCCUPANCY ->
                    bookingRepository
                        .bookingsBetween(businessId, range.start, range.end)
                        .map { ReportData.Occupancy(OccupancyCalculator.calculate(it, range)) }
                ReportType.EVENT_TYPES ->
                    bookingRepository
                        .bookingsBetween(businessId, range.start, range.end)
                        .map { ReportData.EventTypes(EventTypeBreakdownCalculator.calculate(it, range)) }
                ReportType.SOURCES ->
                    bookingRepository
                        .bookingsBetween(businessId, range.start, range.end)
                        .map { ReportData.Sources(BookingSourceBreakdownCalculator.calculate(it, range)) }
                ReportType.EXPENSE_SUMMARY ->
                    combine(
                        reportsRepository.expensesBetween(businessId, range.start, range.end),
                        expensesRepository.partiesWithBalance(businessId),
                        inventoryPurchases(businessId, range),
                    ) { expenses, parties, purchases ->
                        val names = parties.associate { it.party.id to it.party.name }
                        val personalIds = parties.filter { !it.party.businessRelated }.map { it.party.id }.toSet()
                        // The unknown-party fallback is substituted with the localized
                        // label at the presentation layer; the sentinel never renders.
                        ReportData.Expenses(
                            months = ExpenseSummaryCalculator.byMonth(expenses, purchases, range, clock.zone, personalIds),
                            rows = ExpenseSummaryCalculator.calculate(expenses, names, UNKNOWN_PARTY_SENTINEL, personalIds),
                        )
                    }
                ReportType.PROFIT ->
                    combine(
                        reportsRepository.paymentsBetween(businessId, range.start, range.end),
                        reportsRepository.expensesBetween(businessId, range.start, range.end),
                        inventoryPurchases(businessId, range),
                        expensesRepository.partiesWithBalance(businessId),
                    ) { payments, expenses, purchases, parties ->
                        val personalIds = parties.filter { !it.party.businessRelated }.map { it.party.id }.toSet()
                        ReportData.Profit(ProfitCalculator.calculate(payments, expenses, purchases, range, clock.zone, personalIds))
                    }
                ReportType.PERSONAL_EXPENSES ->
                    combine(
                        reportsRepository.expensesBetween(businessId, range.start, range.end),
                        expensesRepository.partiesWithBalance(businessId),
                    ) { expenses, parties ->
                        val names = parties.associate { it.party.id to it.party.name }
                        val personalIds = parties.filter { !it.party.businessRelated }.map { it.party.id }.toSet()
                        ReportData.PersonalExpenses(
                            PersonalExpensesCalculator.calculate(expenses, personalIds, names, UNKNOWN_PARTY_SENTINEL, range),
                        )
                    }
                ReportType.INVENTORY_VALUATION ->
                    inventoryOverviewRepository
                        .currentInventory(businessId)
                        .map { ReportData.Inventory(InventoryValuationCalculator.calculate(it)) }
                ReportType.COLLECTION ->
                    bookingsWithPayments(businessId, range) { bookings, payments ->
                        ReportData.Collection(CollectionEfficiencyCalculator.calculate(bookings, payments))
                    }
            }

        /**
         * Inventory `add` transactions whose transaction time falls in [range] as
         * device-zone-local days — the inventory-purchases spend input of the money
         * reports (ADR-026). End bound is exclusive-next-day so the whole last day counts.
         */
        private fun inventoryPurchases(
            businessId: String,
            range: ReportDateRange,
        ): Flow<List<InventoryTransaction>> =
            reportsRepository.inventoryPurchasesBetween(
                businessId = businessId,
                fromInclusive = range.start.atStartOfDay(clock.zone).toInstant(),
                toExclusive =
                    range.end
                        .plusDays(1)
                        .atStartOfDay(clock.zone)
                        .toInstant(),
            )

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun bookingsWithPayments(
            businessId: String,
            range: ReportDateRange,
            transform: (
                List<com.itsluminous.samaroh.core.model.Booking>,
                List<com.itsluminous.samaroh.core.model.BookingPayment>,
            ) -> ReportData,
        ): Flow<ReportData> =
            bookingRepository
                .bookingsBetween(businessId, range.start, range.end)
                .flatMapLatest { bookings ->
                    if (bookings.isEmpty()) {
                        flowOf(transform(bookings, emptyList()))
                    } else {
                        bookingRepository
                            .paymentsForBookings(bookings.map { it.id })
                            .map { payments -> transform(bookings, payments) }
                    }
                }

        companion object {
            const val REPORT_TYPE_ARG = "reportType"

            /** Marks expense rows whose party row no longer exists; replaced with a localized label in the UI. */
            const val UNKNOWN_PARTY_SENTINEL = "\u0000unknown-party"
        }
    }
