package com.itsluminous.samaroh.feature.expenses.attachments

import android.content.Context
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.drive.DriveService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides [AttachmentCompressor] with its production IO dispatcher (tests construct it directly). */
@Module
@InstallIn(SingletonComponent::class)
object AttachmentsModule {
    @Provides
    @Singleton
    fun provideAttachmentCompressor(
        @ApplicationContext context: Context,
    ): AttachmentCompressor = AttachmentCompressor(context)

    /** View-attachment source resolution (ADR-052); shares the compressor's cache dir. */
    @Provides
    @Singleton
    fun provideAttachmentContentResolver(
        compressor: AttachmentCompressor,
        driveService: DriveService,
        googleAccountLinker: GoogleAccountLinker,
        ledgerRepository: ExpensesLedgerRepository,
    ): AttachmentContentResolver =
        AttachmentContentResolver(
            attachmentsDir = compressor::attachmentsDir,
            driveService = driveService,
            googleAccountLinker = googleAccountLinker,
            ledgerRepository = ledgerRepository,
        )
}
