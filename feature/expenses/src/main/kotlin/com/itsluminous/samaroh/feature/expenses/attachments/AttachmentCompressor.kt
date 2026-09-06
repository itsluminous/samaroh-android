package com.itsluminous.samaroh.feature.expenses.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.itsluminous.samaroh.core.designsystem.imaging.CompressionSpec
import com.itsluminous.samaroh.core.designsystem.imaging.ImageCompression
import com.itsluminous.samaroh.core.designsystem.imaging.decodeUprightImageBlocking
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Light compression for expense-invoice attachments (§4.2): invoices must stay READABLE,
 * so images are re-encoded upright (EXIF honored, ADR-050) at the light
 * [CompressionSpec.DocumentLight] level — and when re-encoding would not actually shrink
 * an already-efficient image, the original bytes are kept verbatim. PDFs are copied
 * byte-identically through a bounded stream, refused honestly as [PrepareResult.TooLarge]
 * beyond [maxDocumentBytes]. Output lands in app-private storage and doubles as the local
 * cache while the Google Drive upload pends.
 */
class AttachmentCompressor(
    private val context: Context,
    /**
     * Resolved at prepare time so the Settings "Image quality" preference (ADR-053)
     * applies to the NEXT attachment without any restart; the default is the fixed
     * ADR-050 level for tests and pref-less construction.
     */
    private val specProvider: suspend () -> CompressionSpec = { CompressionSpec.DocumentLight },
    private val maxDocumentBytes: Long = MAX_DOCUMENT_BYTES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** A prepared local file ready to persist as attachment metadata + queue for upload. */
    data class Prepared(
        val file: File,
        val mimeType: String,
        val fileName: String,
    )

    /** Outcome of preparing one picked/captured attachment — errors stay distinguishable. */
    sealed interface PrepareResult {
        data class Ready(
            val prepared: Prepared,
        ) : PrepareResult

        /** Refused up-front: the document exceeds [maxDocumentBytes] (declared or mid-copy). */
        data object TooLarge : PrepareResult

        /** The content could not be read or decoded (revoked grant, vanished document). */
        data object Unreadable : PrepareResult
    }

    /**
     * Reads [uri] and produces the compressed/copied local file. [mimeType] comes from
     * the picker that produced the uri. Callers map [PrepareResult.TooLarge] and
     * [PrepareResult.Unreadable] to distinct localized messages.
     */
    suspend fun prepare(
        uri: Uri,
        mimeType: String,
        displayName: String,
    ): PrepareResult =
        withContext(ioDispatcher) {
            val result =
                runCatching {
                    if (mimeType.startsWith("image/")) {
                        compressImage(uri, mimeType, displayName, specProvider())
                    } else {
                        copyUntouched(uri, mimeType, displayName)
                    }
                }.onFailure { Log.w(TAG, "attachment prepare threw (mime=$mimeType, name=$displayName)", it) }
                    .getOrDefault(PrepareResult.Unreadable)
            // The phone-evidence run proved a silent failure here is undiagnosable from
            // logcat — always leave a breadcrumb (no PII beyond the picked name).
            when (result) {
                is PrepareResult.Ready ->
                    Log.i(TAG, "attachment prepared (name=${result.prepared.fileName}, bytes=${result.prepared.file.length()})")
                else -> Log.w(TAG, "attachment prepare failed: $result (mime=$mimeType, name=$displayName)")
            }
            result
        }

    /** Compresses an already-local capture (camera flow) in place of the raw file. */
    suspend fun prepareCapturedImage(file: File): PrepareResult = prepare(Uri.fromFile(file), MIME_JPEG, file.name)

    /** App-private directory for prepared attachments (matches expenses_file_paths.xml). */
    fun attachmentsDir(): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** A fresh capture target for the camera contract. */
    fun newCaptureFile(): File = File(attachmentsDir(), "capture-${UUID.randomUUID()}.jpg")

    private fun compressImage(
        uri: Uri,
        mimeType: String,
        displayName: String,
        spec: CompressionSpec,
    ): PrepareResult {
        // Upright, bounded decode via the shared pipeline — camera JPEGs carry EXIF
        // rotation that the old path ignored (sideways invoices, ADR-050). Blocking
        // variant: prepare() already runs on the injected IO dispatcher.
        val upright = decodeUprightImageBlocking(context, uri, spec.maxDimensionPx) ?: return PrepareResult.Unreadable
        val outName = "${baseName(displayName)}-${UUID.randomUUID().toString().take(8)}.jpg"
        val outFile = File(attachmentsDir(), outName)
        val encodedBytes =
            try {
                ImageCompression.encodeToFile(upright.bitmap, spec, outFile)
            } finally {
                upright.bitmap.recycle()
            }
        // Keep the original bytes when re-encoding did not help (a small,
        // already-efficient image) — but only when nothing had to change:
        // no downsize and no EXIF rotation.
        val sourceBytes = declaredSize(uri)
        if (!upright.wasDownsized && upright.rotationDegrees == 0 && sourceBytes != null && sourceBytes <= encodedBytes) {
            outFile.delete()
            return copyUntouched(uri, mimeType, displayName)
        }
        return PrepareResult.Ready(Prepared(file = outFile, mimeType = MIME_JPEG, fileName = outName))
    }

    private fun copyUntouched(
        uri: Uri,
        mimeType: String,
        displayName: String,
    ): PrepareResult {
        // Refuse absurd picks before copying a single byte, when the provider
        // is willing to say how big the content is.
        declaredSize(uri)?.let { size -> if (size > maxDocumentBytes) return PrepareResult.TooLarge }
        val outName = "${baseName(displayName)}-${UUID.randomUUID().toString().take(8)}${extensionOf(displayName)}"
        val outFile = File(attachmentsDir(), outName)
        val copied =
            context.contentResolver.openInputStream(uri)?.use { input -> copyBounded(input, outFile, maxDocumentBytes) }
                ?: return PrepareResult.Unreadable
        if (copied < 0) {
            // The size column was absent or lied; the bounded copy is the backstop.
            outFile.delete()
            return PrepareResult.TooLarge
        }
        return PrepareResult.Ready(Prepared(file = outFile, mimeType = mimeType, fileName = outName))
    }

    /**
     * Streams [input] into [target] through a fixed-size buffer — memory use is
     * O(buffer), never O(content). Returns the byte count copied, or -1 when the
     * content exceeds [capBytes] (the partial [target] is left for the caller to delete).
     */
    private fun copyBounded(
        input: InputStream,
        target: File,
        capBytes: Long,
    ): Long {
        target.outputStream().use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return total
                total += read
                if (total > capBytes) return -1L
                out.write(buffer, 0, read)
            }
        }
    }

    /** The provider-declared content size, or null when unavailable. */
    private fun declaredSize(uri: Uri): Long? =
        runCatching {
            if (uri.scheme == "file") return@runCatching uri.path?.let { File(it).length().takeIf { len -> len > 0 } }
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        cursor.getLong(0).takeIf { it >= 0 }
                    } else {
                        null
                    }
                }
        }.getOrNull()

    private fun baseName(displayName: String): String = displayName.substringBeforeLast('.').ifBlank { "attachment" }

    private fun extensionOf(displayName: String): String =
        displayName.substringAfterLast('.', missingDelimiterValue = "").let { if (it.isEmpty()) "" else ".$it" }

    companion object {
        /**
         * Largest accepted document (PDF) attachment: generous for real invoices,
         * refuses pathological picks before disk fills — honest [PrepareResult.TooLarge].
         */
        const val MAX_DOCUMENT_BYTES = 25_000_000L

        const val MIME_JPEG = "image/jpeg"
        const val MIME_PDF = "application/pdf"
        private const val DIR_NAME = "expense_attachments"

        /** Logcat breadcrumb tag for attachment-import outcomes (evidence run 2026-09-06). */
        private const val TAG = "SamarohAttach"
    }
}
