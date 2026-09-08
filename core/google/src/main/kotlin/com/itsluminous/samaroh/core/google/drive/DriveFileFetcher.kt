package com.itsluminous.samaroh.core.google.drive

import android.util.Log
import com.itsluminous.samaroh.core.google.auth.GoogleAccountLinker
import com.itsluminous.samaroh.core.google.auth.GoogleLinkState
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of the shared own-token → public-link download ladder. */
sealed interface DriveFetchResult {
    /** The bytes are in the caller's target file. */
    data object Success : DriveFetchResult

    /**
     * Both rungs failed. [error] is the LAST failure (the public rung — a
     * [com.itsluminous.samaroh.core.google.rest.GoogleApiException] means Drive answered
     * but withheld the file, anything else is typically offline); [linked] whether a
     * Google account was linked when the ladder ran — callers combine the two for their
     * link-prompt policy.
     */
    data class Failure(
        val error: Throwable?,
        val linked: Boolean,
    ) : DriveFetchResult
}

/**
 * The ADR-052/059/063 Drive download ladder, shared by expense attachments and item
 * photos (previously duplicated in both features):
 *
 * 1. Google linked → own-token `files.get?alt=media` (the user's own uploads);
 * 2. own-token failed OR not linked → PUBLIC LINK download (`uc?export=download`, no
 *    credentials) — files are shared anyone-with-link at upload/repair, so a member
 *    whose token cannot read another account's file still gets the bytes.
 *
 * An empty result file counts as failure on either rung (an interstitial page must
 * never be cached as content). Writes into [fetchInto]'s `target`; deletes it on
 * failure. Caching convention, target naming, temp-file/rename and link-prompt policy
 * deliberately stay with the callers.
 */
@Singleton
class DriveFileFetcher
    @Inject
    constructor(
        private val driveService: DriveService,
        private val googleAccountLinker: GoogleAccountLinker,
    ) {
        suspend fun fetchInto(
            driveFileId: String,
            target: File,
        ): DriveFetchResult {
            val linked = googleAccountLinker.linkState.first() is GoogleLinkState.Linked
            if (linked) {
                val own = runCatching { driveService.downloadFile(driveFileId, target) }
                if (own.isSuccess && target.length() > 0L) return DriveFetchResult.Success
                Log.i(TAG, "own-token download failed (drive=$driveFileId) — trying public link", own.exceptionOrNull())
            }
            // Member fallback (ADR-059): anyone-with-link files download without credentials.
            val public = runCatching { driveService.downloadPublicFile(driveFileId, target) }
            if (public.isSuccess && target.length() > 0L) return DriveFetchResult.Success
            Log.w(TAG, "public-link download failed (drive=$driveFileId)", public.exceptionOrNull())
            target.delete()
            return DriveFetchResult.Failure(public.exceptionOrNull(), linked)
        }

        private companion object {
            const val TAG = "DriveFileFetcher"
        }
    }
