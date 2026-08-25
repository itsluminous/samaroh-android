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
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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

    companion object {
        @Provides fun provideClock(): Clock = Clock.systemUTC()
    }
}
