package com.itsluminous.samaroh.feature.booking.di

import com.itsluminous.samaroh.feature.booking.domain.BookingActorProvider
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.domain.EventTypesProvider
import com.itsluminous.samaroh.feature.booking.domain.SessionBookingActorProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BookingFeatureModule {
    /**
     * Session-aware actor (Wave-1 integration, ADR-017): built on the core:data
     * current-user source; signed-out/offline falls back to owner-mode.
     */
    @Binds
    abstract fun bindBookingActorProvider(impl: SessionBookingActorProvider): BookingActorProvider

    /** Built-in event types come from the shared submodule's event-types.json (§4.1). */
    @Binds
    abstract fun bindEventTypeCatalog(impl: EventTypesProvider): EventTypeCatalog
}
