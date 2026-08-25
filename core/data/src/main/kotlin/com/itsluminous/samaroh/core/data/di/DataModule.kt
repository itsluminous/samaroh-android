package com.itsluminous.samaroh.core.data.di

import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.ExpensesRepository
import com.itsluminous.samaroh.core.data.repository.InventoryRepository
import com.itsluminous.samaroh.core.data.repository.MemberRepository
import com.itsluminous.samaroh.core.data.repository.RoomBookingRepository
import com.itsluminous.samaroh.core.data.repository.RoomBusinessRepository
import com.itsluminous.samaroh.core.data.repository.RoomExpensesRepository
import com.itsluminous.samaroh.core.data.repository.RoomInventoryRepository
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

    @Binds abstract fun bindInventoryRepository(impl: RoomInventoryRepository): InventoryRepository

    @Binds abstract fun bindBusinessRepository(impl: RoomBusinessRepository): BusinessRepository

    @Binds abstract fun bindMemberRepository(impl: RoomMemberRepository): MemberRepository

    companion object {
        @Provides fun provideClock(): Clock = Clock.systemUTC()
    }
}
