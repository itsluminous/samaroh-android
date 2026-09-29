package com.itsluminous.samaroh.feature.files.open

import android.content.Context
import com.itsluminous.samaroh.core.data.repository.FileWithLocalState
import com.itsluminous.samaroh.core.data.repository.FilesRepository
import com.itsluminous.samaroh.core.google.drive.DriveFetchResult
import com.itsluminous.samaroh.core.google.drive.DriveFileFetcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where a tapped file's bytes can (or cannot) come from. */
sealed interface FileOpenResult {
    /** The bytes are on this device (staged original, cached, or just downloaded). */
    data class Ready(
        val file: File,
    ) : FileOpenResult

    /** Neither the cache nor Drive delivered the bytes (offline, or Drive withheld the file). */
    data object Failed : FileOpenResult
}

/**
 * Resolves the bytes of a Files-module row (ADR-085, design D16) the ADR-052/059 way:
 * live `local_cache_path` (a staged original or an earlier download) → the shared
 * [DriveFileFetcher] ladder (own token, then the anyone-with-link public download) into
 * `files-cache/{id}`, stamping the row's device-only cache path. Used for the in-app
 * image viewer and for Download; non-image opens go to the Drive viewer URL instead.
 */
@Singleton
class FileContentResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val driveFileFetcher: DriveFileFetcher,
        private val repository: FilesRepository,
    ) {
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        suspend fun resolve(row: FileWithLocalState): FileOpenResult {
            val cached = row.localCachePath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
            if (cached != null) return FileOpenResult.Ready(cached)
            val driveFileId = row.file.driveFileId ?: return FileOpenResult.Failed
            return withContext(ioDispatcher) {
                val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
                val target = File(dir, row.file.id)
                val tmp = File(dir, "${row.file.id}.tmp")
                when (driveFileFetcher.fetchInto(driveFileId, tmp)) {
                    DriveFetchResult.Success -> {
                        if (!tmp.renameTo(target)) {
                            tmp.copyTo(target, overwrite = true)
                            tmp.delete()
                        }
                        repository.updateLocalCachePath(row.file.id, target.absolutePath)
                        FileOpenResult.Ready(target)
                    }
                    is DriveFetchResult.Failure -> {
                        tmp.delete()
                        FileOpenResult.Failed
                    }
                }
            }
        }

        /** Removes the device copy of a tombstoned row (staged original or cache). */
        fun deleteLocalCopy(row: FileWithLocalState) {
            row.localCachePath?.let { runCatching { File(it).delete() } }
        }

        companion object {
            const val CACHE_DIR = "files-cache"
        }
    }
