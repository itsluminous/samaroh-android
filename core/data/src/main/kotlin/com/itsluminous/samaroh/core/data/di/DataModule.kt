package com.itsluminous.samaroh.core.data.di

import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.repository.FifoInventoryRepository
import com.itsluminous.samaroh.core.data.repository.InventoryOverviewRepository
import com.itsluminous.samaroh.core.data.repository.InventoryRepository
import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.data.repository.RoomBookingRepository
import com.itsluminous.samaroh.core.data.repository.RoomBusinessRepository
import com.itsluminous.samaroh.core.data.repository.RoomExpensesRepository
import com.itsluminous.samaroh.core.data.repository.RoomMemberRepository
import com.itsluminous.samaroh.core.data.session.DefaultSignOutCleaner
import com.itsluminous.samaroh.core.data.session.SessionScopedStore
import com.itsluminous.samaroh.core.data.session.SignOutCleaner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import java.time.Clock

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun bindBookingRepository(impl: RoomBookingRepository): BookingRepository

    @Binds abstract fun bindExpensesRepository(impl: RoomExpensesRepository): ExpensesRepository

    // FIFO-aware decorator over RoomInventoryRepository (W1-C, docs/decisions.md ADR-007).
    @Binds abstract fun bindInventoryRepository(impl: FifoInventoryRepository): InventoryRepository

    @Binds abstract fun bindInventoryOverviewRepository(impl: FifoInventoryRepository): InventoryOverviewRepository

    @Binds abstract fun bindBusinessRepository(impl: RoomBusinessRepository): BusinessRepository

    @Binds abstract fun bindMemberRepository(impl: RoomMemberRepository): MemberRepository

    /** Sign-out local-data wipe (ADR-040). */
    @Binds abstract fun bindSignOutCleaner(impl: DefaultSignOutCleaner): SignOutCleaner

    /** Modules contribute [SessionScopedStore]s via `@IntoSet`; valid when none do (ADR-040). */
    @Multibinds abstract fun sessionScopedStores(): Set<SessionScopedStore>

    companion object {
        @Provides fun provideClock(): Clock = Clock.systemUTC()
    }
}
