package com.itsluminous.samaroh.core.designsystem.imaging

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves a copy of an app-private image into the device's public Downloads collection
 * (the viewer's "Save to device" action, ADR-053).
 *
 * - API 29+: `MediaStore.Downloads` insert with `IS_PENDING` — NO storage permission
 *   needed; the system uniquifies colliding display names.
 * - API 26–28: legacy public-directory write + media scan, which requires the runtime
 *   `WRITE_EXTERNAL_STORAGE` permission (declared with `maxSdkVersion="28"`); callers
 *   get [SaveResult.NeedsPermission] and re-invoke after requesting it.
 */
object MediaStoreImageSaver {
    /** Outcome of one save; [SaveResult.Saved.locationLabel] feeds the success snackbar. */
    sealed interface SaveResult {
        data class Saved(
            /** User-facing folder the copy landed in, e.g. `Download/Samaroh`. */
            val locationLabel: String,
        ) : SaveResult

        /** Legacy API (26–28) only: the caller must request `WRITE_EXTERNAL_STORAGE` first. */
        data object NeedsPermission : SaveResult

        data object Failed : SaveResult
    }

    /** Subfolder of the public Downloads collection the copies land in. */
    const val TARGET_SUBDIR = "Samaroh"

    /** The user-facing location label (also the API-29+ `RELATIVE_PATH`). */
    val LOCATION_LABEL = "${Environment.DIRECTORY_DOWNLOADS}/$TARGET_SUBDIR"

    suspend fun saveImage(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): SaveResult =
        withContext(ioDispatcher) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, source, displayName, mimeType)
            } else {
                saveLegacy(context, source, displayName, mimeType)
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
    ): SaveResult {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, LOCATION_LABEL)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: return SaveResult.Failed
        return runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: error("no output stream for $uri")
            // Publish: clear IS_PENDING so the file becomes visible to other apps.
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            SaveResult.Saved(LOCATION_LABEL) as SaveResult
        }.getOrElse { error ->
            Log.w(TAG, "MediaStore save failed (name=$displayName)", error)
            runCatching { resolver.delete(uri, null, null) }
            SaveResult.Failed
        }
    }

    private fun saveLegacy(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
    ): SaveResult {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return SaveResult.NeedsPermission
        return runCatching {
            val dir =
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), TARGET_SUBDIR)
                    .apply { mkdirs() }
            val target = uniqueTarget(dir, displayName)
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
            SaveResult.Saved(LOCATION_LABEL) as SaveResult
        }.getOrElse { error ->
            Log.w(TAG, "legacy Downloads save failed (name=$displayName)", error)
            SaveResult.Failed
        }
    }

    /** `bill.jpg` → `bill (1).jpg` … — manual uniquifying the legacy path lacks. */
    private fun uniqueTarget(
        dir: File,
        displayName: String,
    ): File {
        val candidate = File(dir, displayName)
        if (!candidate.exists()) return candidate
        val base = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', missingDelimiterValue = "").let { if (it.isEmpty()) "" else ".$it" }
        var index = 1
        while (true) {
            val next = File(dir, "$base ($index)$ext")
            if (!next.exists()) return next
            index++
        }
    }

    private const val TAG = "SamarohImageSave"
}
