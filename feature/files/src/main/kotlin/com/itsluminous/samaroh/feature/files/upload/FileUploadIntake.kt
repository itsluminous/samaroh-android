package com.itsluminous.samaroh.feature.files.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.itsluminous.samaroh.core.data.repository.FilesRepository
import com.itsluminous.samaroh.core.data.share.SharedFile
import com.itsluminous.samaroh.core.data.sync.SyncScheduler
import com.itsluminous.samaroh.core.model.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of staging one picked/shared batch (design D13 limits applied before any I/O). */
data class UploadIntakeResult(
    /** Rows staged in Room + outbox (they upload on the next connected, linked sync). */
    val staged: List<FileItem>,
    /** Display names of files skipped for exceeding 25 MiB (`files.upload.too_large`). */
    val tooLarge: List<String>,
    /** Display names whose stream could not be read (`files.upload.failed`). */
    val unreadable: List<String>,
    /** True when more than [FileItem.MAX_BATCH] files were offered (`files.upload.too_many`). */
    val tooMany: Boolean,
)

/**
 * Stages picked/shared files into the Files module (ADR-085): copies each content URI's
 * ORIGINAL bytes into the app-private `files-staging/` dir (never recompressed — this is
 * file storage), writes the metadata row with a null Drive id + the outbox op, and
 * nudges the scheduler. The sync drain performs the Drive upload before pushing the row
 * (`FilesUploader`); unlinked/offline devices simply keep the rows pending.
 */
@Singleton
class FileUploadIntake
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: FilesRepository,
        private val syncScheduler: SyncScheduler,
        private val clock: Clock,
    ) {
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        /** Describes picker URIs the same way the share sheet does (name/MIME/size from the provider). */
        fun describe(uris: List<Uri>): List<SharedFile> =
            uris.map { uri ->
                var name: String? = null
                var size: Long? = null
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst()) {
                            if (!it.isNull(0)) name = it.getString(0)
                            if (!it.isNull(1)) size = it.getLong(1)
                        }
                    }
                }
                SharedFile(
                    uri = uri,
                    mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: DEFAULT_MIME,
                    displayName = name ?: uri.lastPathSegment ?: DEFAULT_NAME,
                    sizeBytes = size,
                )
            }

        suspend fun stage(
            businessId: String,
            userId: String,
            folderId: String?,
            files: List<SharedFile>,
        ): UploadIntakeResult =
            withContext(ioDispatcher) {
                val tooMany = files.size > FileItem.MAX_BATCH
                val accepted = files.take(FileItem.MAX_BATCH)
                val staged = mutableListOf<FileItem>()
                val tooLarge = mutableListOf<String>()
                val unreadable = mutableListOf<String>()
                val dir = stagingDir()
                for (shared in accepted) {
                    if ((shared.sizeBytes ?: 0L) > FileItem.MAX_SIZE_BYTES) {
                        tooLarge += shared.displayName
                        continue
                    }
                    val id = UUID.randomUUID().toString()
                    val target = File(dir, id)
                    val copied = copyBounded(shared.uri, target)
                    when {
                        copied == null -> {
                            target.delete()
                            unreadable += shared.displayName
                        }
                        copied > FileItem.MAX_SIZE_BYTES -> {
                            target.delete()
                            tooLarge += shared.displayName
                        }
                        else -> {
                            val now = clock.instant()
                            val row =
                                FileItem(
                                    id = id,
                                    businessId = businessId,
                                    folderId = folderId,
                                    name = sanitizeName(shared.displayName),
                                    mimeType = shared.mimeType.ifBlank { DEFAULT_MIME },
                                    sizeBytes = copied,
                                    driveFileId = null,
                                    createdBy = userId,
                                    createdAt = now,
                                    updatedAt = now,
                                )
                            repository.stageFile(row, target.absolutePath)
                            staged += row
                        }
                    }
                }
                if (staged.isNotEmpty()) syncScheduler.requestImmediateSync()
                UploadIntakeResult(staged, tooLarge, tooMany = tooMany, unreadable = unreadable)
            }

        /** Copies the stream, aborting past the cap; null when the URI cannot be opened/read. */
        private fun copyBounded(
            uri: Uri,
            target: File,
        ): Long? =
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > FileItem.MAX_SIZE_BYTES) return@runCatching total
                            out.write(buffer, 0, read)
                        }
                        total
                    }
                }
            }.getOrNull()

        /** Server CHECK on `files.name`: 1–255 chars, no `/` (design D12); the ORIGINAL name otherwise. */
        private fun sanitizeName(displayName: String): String {
            val cleaned = displayName.replace('/', '-').trim().ifEmpty { DEFAULT_NAME }
            return if (cleaned.length <= MAX_NAME) cleaned else cleaned.takeLast(MAX_NAME)
        }

        private fun stagingDir(): File = File(context.filesDir, STAGING_DIR).apply { mkdirs() }

        companion object {
            const val STAGING_DIR = "files-staging"
            const val DEFAULT_MIME = "application/octet-stream"
            private const val DEFAULT_NAME = "file"
            private const val MAX_NAME = 255
        }
    }
