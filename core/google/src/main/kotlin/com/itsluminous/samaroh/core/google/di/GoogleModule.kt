package com.itsluminous.samaroh.core.google.di

import com.itsluminous.samaroh.core.data.attachments.AttachmentUploadQueue
import com.itsluminous.samaroh.core.data.session.SessionScopedStore
import com.itsluminous.samaroh.core.data.sync.AttachmentUploader
import com.itsluminous.samaroh.core.google.auth.CredentialManagerGoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleAccessTokenProvider
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.PlayServicesAccessTokenProvider
import com.itsluminous.samaroh.core.google.calendar.CalendarService
import com.itsluminous.samaroh.core.google.calendar.GcalSyncStateStore
import com.itsluminous.samaroh.core.google.calendar.RestCalendarService
import com.itsluminous.samaroh.core.google.drive.DriveAttachmentUploader
import com.itsluminous.samaroh.core.google.drive.DriveBackedAttachmentUploadQueue
import com.itsluminous.samaroh.core.google.drive.DriveService
import com.itsluminous.samaroh.core.google.drive.DriveUploader
import com.itsluminous.samaroh.core.google.drive.RestDriveService
import com.itsluminous.samaroh.core.google.drive.RestDriveUploader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class GoogleModule {
    @Binds abstract fun bindGoogleAccountLinker(impl: CredentialManagerGoogleAccountLinker): GoogleAccountLinker

    @Binds abstract fun bindAccessTokenProvider(impl: PlayServicesAccessTokenProvider): GoogleAccessTokenProvider

    @Binds abstract fun bindDriveService(impl: RestDriveService): DriveService

    @Binds abstract fun bindDriveUploader(impl: RestDriveUploader): DriveUploader

    @Binds abstract fun bindCalendarService(impl: RestCalendarService): CalendarService

    /** Satisfies `core:sync`'s optional uploader — attachments upload during the outbox drain (ADR-018). */
    @Binds abstract fun bindAttachmentUploader(impl: DriveAttachmentUploader): AttachmentUploader

    /** Supersedes `core:data`'s local-only placeholder queue (ADR-018). */
    @Binds abstract fun bindAttachmentUploadQueue(impl: DriveBackedAttachmentUploadQueue): AttachmentUploadQueue

    /** Sign-out wipes the per-device gcal push state with the rest of the local data (ADR-040). */
    @Binds
    @IntoSet
    abstract fun bindGcalSessionScopedStore(impl: GcalSyncStateStore): SessionScopedStore
}
