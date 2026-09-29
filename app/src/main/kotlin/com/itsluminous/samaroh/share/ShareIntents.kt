package com.itsluminous.samaroh.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.itsluminous.samaroh.core.data.share.SharedFile

/**
 * Parsing of the unified "Save to Samaroh" share target's intents (ADR-086, design
 * D18): `ACTION_SEND` (one `EXTRA_STREAM`) and `ACTION_SEND_MULTIPLE` (a list) of ANY
 * MIME. Separated from the activity so the payload shape is unit-testable; the concrete
 * MIME/name/size come from the ContentResolver when the sender only declared a wildcard.
 */
object ShareIntents {
    /** The shared files of [intent], or an empty list when it is not a share (or carries no usable stream). */
    fun parse(
        context: Context,
        intent: Intent?,
    ): List<SharedFile> {
        val uris =
            when (intent?.action) {
                Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE ->
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> return emptyList()
            }
        val declared = intent.type?.takeIf { !it.endsWith("/*") && it != "*/*" }
        return uris.map { uri -> describe(context, uri, declared) }
    }

    private fun describe(
        context: Context,
        uri: Uri,
        declaredMime: String?,
    ): SharedFile {
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        if (!cursor.isNull(0)) name = cursor.getString(0)
                        if (!cursor.isNull(1)) size = cursor.getLong(1)
                    }
                }
        }
        val resolved = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return SharedFile(
            uri = uri,
            mimeType = resolved ?: declaredMime ?: DEFAULT_MIME,
            displayName = name ?: uri.lastPathSegment ?: DEFAULT_NAME,
            sizeBytes = size,
        )
    }

    private const val DEFAULT_MIME = "application/octet-stream"
    private const val DEFAULT_NAME = "file"
}
