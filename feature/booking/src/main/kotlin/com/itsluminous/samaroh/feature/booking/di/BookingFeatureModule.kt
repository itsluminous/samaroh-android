package com.itsluminous.samaroh.feature.booking.di

import com.itsluminous.samaroh.core.data.reminders.ReminderTestFirer
import com.itsluminous.samaroh.core.data.sync.PostSyncHook
import com.itsluminous.samaroh.feature.booking.domain.BookingActorProvider
import com.itsluminous.samaroh.feature.booking.domain.EventTypeCatalog
import com.itsluminous.samaroh.feature.booking.domain.EventTypesProvider
import com.itsluminous.samaroh.feature.booking.domain.SessionBookingActorProvider
import com.itsluminous.samaroh.feature.booking.reminders.BookingReminderTestFirer
import com.itsluminous.samaroh.feature.booking.reminders.DataStoreNotificationPromptPrefs
import com.itsluminous.samaroh.feature.booking.reminders.NotificationPromptPrefs
import com.itsluminous.samaroh.feature.booking.reminders.ReminderPostSyncHook
import com.itsluminous.samaroh.feature.booking.ui.calendar.BookingCalendarPrefs
import com.itsluminous.samaroh.feature.booking.ui.calendar.DataStoreBookingCalendarPrefs
import com.itsluminous.samaroh.feature.booking.ui.form.BookingFormFieldPrefs
import com.itsluminous.samaroh.feature.booking.ui.form.DataStoreBookingFormFieldPrefs
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

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

    /** Booking-form optional-field visibility from the shared settings DataStore (ADR-020). */
    @Binds
    abstract fun bindBookingFormFieldPrefs(impl: DataStoreBookingFormFieldPrefs): BookingFormFieldPrefs

    /** Calendar icon-watermark opacity from the shared settings DataStore. */
    @Binds
    abstract fun bindBookingCalendarPrefs(impl: DataStoreBookingCalendarPrefs): BookingCalendarPrefs

    /** "POST_NOTIFICATIONS was requested once" flag for the form-open gate (ADR-044). */
    @Binds
    abstract fun bindNotificationPromptPrefs(impl: DataStoreNotificationPromptPrefs): NotificationPromptPrefs

    /** Post-sync reminder re-planning + daily-worker registration (ADR-024). */
    @Binds
    @IntoSet
    abstract fun bindReminderPostSyncHook(impl: ReminderPostSyncHook): PostSyncHook

    /** Settings Test button fires a sample through the real reminder pipeline (ADR-045). */
    @Binds
    abstract fun bindReminderTestFirer(impl: BookingReminderTestFirer): ReminderTestFirer
}
