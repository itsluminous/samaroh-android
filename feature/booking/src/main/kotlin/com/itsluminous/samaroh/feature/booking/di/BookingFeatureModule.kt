package com.itsluminous.samaroh.feature.booking.di

import com.itsluminous.samaroh.feature.booking.domain.BookingActorProvider
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.domain.EventTypesProvider
import com.itsluminous.samaroh.feature.booking.domain.OwnerBookingActorProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BookingFeatureModule {
    /**
     * Default actor seam: device user = business owner (full access) until the auth wave
     * (W1-D) supplies a session-aware provider — swap this single binding at integration.
     */
    @Binds
    abstract fun bindBookingActorProvider(impl: OwnerBookingActorProvider): BookingActorProvider

    /** Built-in event types come from the shared submodule's event-types.json (§4.1). */
    @Binds
    abstract fun bindEventTypeCatalog(impl: EventTypesProvider): EventTypeCatalog
}
