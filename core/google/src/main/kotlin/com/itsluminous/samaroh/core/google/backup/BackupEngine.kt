package com.itsluminous.samaroh.core.google.backup

import android.content.Context
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.google.drive.DriveFileRef
import com.itsluminous.samaroh.core.google.drive.DriveTarget
import com.itsluminous.samaroh.core.google.drive.DriveUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup engine (§4.4): per-table JSON exports + attachments manifest zipped and uploaded
 * to Drive `Samaroh/{Business}/backups/backup-YYYY-MM-DD-HHmm.zip`. On success the
 * business settings' `last_backup_at` is updated through the repository (Room + outbox).
 */
@Singleton
class BackupEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val exporter: BackupExporter,
        private val driveUploader: DriveUploader,
        private val businessRepository: BusinessRepository,
        private val clock: Clock,
    ) {
        suspend fun backUpNow(businessId: String): Result<DriveFileRef> =
            runCatching {
                val business =
                    businessRepository.business(businessId)
                        ?: error("unknown business $businessId")
                val now = clock.instant()
                val content = exporter.export(businessId)
                val manifest =
                    BackupArchive.buildManifest(
                        businessId = businessId,
                        businessName = business.name,
                        createdAt = now.toString(),
                        tables = content.tables,
                        attachments = content.attachments,
                    )
                val fileName = BackupArchive.fileName(LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
                val archive = File.createTempFile("samaroh-backup-", ".zip", context.cacheDir)
                try {
                    archive.outputStream().use { BackupArchive.write(it, manifest, content.tables) }
                    val uploaded =
                        driveUploader
                            .upload(
                                businessName = business.name,
                                target = DriveTarget.Backups,
                                fileName = fileName,
                                mimeType = BackupArchive.MIME_TYPE,
                                sourceFile = archive,
                            ).getOrThrow()
                    markBackupDone(businessId)
                    uploaded
                } finally {
                    archive.delete()
                }
            }

        private suspend fun markBackupDone(businessId: String) {
            val settings = businessRepository.settings(businessId).first() ?: return
            businessRepository.saveSettings(settings.copy(lastBackupAt = clock.instant(), updatedAt = clock.instant()))
        }
    }
