package com.itsluminous.samaroh.core.google.di

import com.itsluminous.samaroh.core.google.auth.CredentialManagerGoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleAccessTokenProvider
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.PlayServicesAccessTokenProvider
import com.itsluminous.samaroh.core.google.calendar.CalendarService
import com.itsluminous.samaroh.core.google.calendar.RestCalendarService
import com.itsluminous.samaroh.core.google.drive.DriveService
import com.itsluminous.samaroh.core.google.drive.DriveUploader
import com.itsluminous.samaroh.core.google.drive.RestDriveService
import com.itsluminous.samaroh.core.google.drive.RestDriveUploader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class GoogleModule {
    @Binds abstract fun bindGoogleAccountLinker(impl: CredentialManagerGoogleAccountLinker): GoogleAccountLinker

    @Binds abstract fun bindAccessTokenProvider(impl: PlayServicesAccessTokenProvider): GoogleAccessTokenProvider

    @Binds abstract fun bindDriveService(impl: RestDriveService): DriveService

    @Binds abstract fun bindDriveUploader(impl: RestDriveUploader): DriveUploader

    @Binds abstract fun bindCalendarService(impl: RestCalendarService): CalendarService
}
