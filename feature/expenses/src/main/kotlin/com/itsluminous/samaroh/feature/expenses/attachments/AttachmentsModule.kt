package com.itsluminous.samaroh.feature.expenses.attachments

import android.content.Context
import com.itsluminous.samaroh.core.data.repository.ExpensesLedgerRepository
import com.itsluminous.samaroh.core.data.settings.ImageQualityPreferences
import com.itsluminous.samaroh.core.designsystem.imaging.CompressionSpec
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.drive.DriveFileFetcher
import com.itsluminous.samaroh.core.google.drive.DriveService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/** Provides [AttachmentCompressor] with its production IO dispatcher (tests construct it directly). */
@Module
@InstallIn(SingletonComponent::class)
object AttachmentsModule {
    @Provides
    @Singleton
    fun provideAttachmentCompressor(
        @ApplicationContext context: Context,
        imageQuality: ImageQualityPreferences,
    ): AttachmentCompressor =
        AttachmentCompressor(
            context = context,
            // ADR-053: the Settings "Bill photos" quality rides the fixed DocumentLight
            // dimensions/format — only the encoder quality is user-tunable.
            specProvider = { CompressionSpec.DocumentLight.copy(quality = imageQuality.billsQuality.first()) },
        )

    /** Viewer delete cascade (ADR-053): tombstone + cache removal + best-effort Drive delete. */
    @Provides
    @Singleton
    fun provideAttachmentDeleter(
        ledgerRepository: ExpensesLedgerRepository,
        driveService: DriveService,
        googleAccountLinker: GoogleAccountLinker,
    ): AttachmentDeleter =
        AttachmentDeleter(
            ledgerRepository = ledgerRepository,
            driveService = driveService,
            googleAccountLinker = googleAccountLinker,
        )

    /** View-attachment source resolution (ADR-052); shares the compressor's cache dir. */
    @Provides
    @Singleton
    fun provideAttachmentContentResolver(
        compressor: AttachmentCompressor,
        driveFileFetcher: DriveFileFetcher,
        ledgerRepository: ExpensesLedgerRepository,
    ): AttachmentContentResolver =
        AttachmentContentResolver(
            attachmentsDir = compressor::attachmentsDir,
            driveFileFetcher = driveFileFetcher,
            ledgerRepository = ledgerRepository,
        )
}
