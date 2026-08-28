package com.itsluminous.samaroh.core.data.di

import com.itsluminous.samaroh.core.data.color.BookingColorCatalog
import com.itsluminous.samaroh.core.data.color.BookingColorsProvider
import com.itsluminous.samaroh.core.data.repository.AssetEventTypeSeedTemplate
import com.itsluminous.samaroh.core.data.repository.EventTypeRepository
import com.itsluminous.samaroh.core.data.repository.EventTypeSeedTemplate
import com.itsluminous.samaroh.core.data.repository.RoomEventTypeRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Event-type preset bindings (ADR-032) — their own module so [DataModule] stays
 * untouched, matching the ExpensesLedgerModule precedent.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EventTypesModule {
    @Binds abstract fun bindEventTypeRepository(impl: RoomEventTypeRepository): EventTypeRepository

    /** Seed template from the shared submodule's event-types.json, labels in English. */
    @Binds abstract fun bindEventTypeSeedTemplate(impl: AssetEventTypeSeedTemplate): EventTypeSeedTemplate

    /** Booking colour palette (ADR-030; moved here from feature:booking in ADR-032). */
    @Binds abstract fun bindBookingColorCatalog(impl: BookingColorsProvider): BookingColorCatalog
}
