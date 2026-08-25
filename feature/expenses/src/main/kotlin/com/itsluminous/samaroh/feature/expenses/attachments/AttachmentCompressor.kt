package com.itsluminous.samaroh.feature.expenses.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Light compression for expense-invoice attachments (§4.2): invoices must stay READABLE,
 * so unlike inventory images (320px WebP) images are re-encoded as JPEG quality
 * [JPEG_QUALITY] capped at [MAX_DIMENSION] px on the longest side, and PDFs are copied
 * byte-identically. Output lands in app-private storage and doubles as the local cache
 * while the Google Drive upload pends.
 */
class AttachmentCompressor(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** A prepared local file ready to persist as attachment metadata + queue for upload. */
    data class Prepared(
        val file: File,
        val mimeType: String,
        val fileName: String,
    )

    /**
     * Reads [uri] and produces the compressed/copied local file. [mimeType] comes from
     * the picker that produced the uri. Returns null when the content cannot be read
     * or decoded (caller shows a localized failure message).
     */
    suspend fun prepare(
        uri: Uri,
        mimeType: String,
        displayName: String,
    ): Prepared? =
        withContext(ioDispatcher) {
            runCatching {
                if (mimeType.startsWith("image/")) {
                    compressImage(uri, displayName)
                } else {
                    copyUntouched(uri, mimeType, displayName)
                }
            }.getOrNull()
        }

    /** Compresses an already-local capture (camera flow) in place of the raw file. */
    suspend fun prepareCapturedImage(file: File): Prepared? = prepare(Uri.fromFile(file), MIME_JPEG, file.name)

    /** App-private directory for prepared attachments (matches expenses_file_paths.xml). */
    fun attachmentsDir(): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** A fresh capture target for the camera contract. */
    fun newCaptureFile(): File = File(attachmentsDir(), "capture-${UUID.randomUUID()}.jpg")

    private fun compressImage(
        uri: Uri,
        displayName: String,
    ): Prepared? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Coarse power-of-two subsampling first (memory-safe), exact scale second.
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded =
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val scaled = scaleToMaxDimension(decoded)

        val outName = "${baseName(displayName)}-${UUID.randomUUID().toString().take(8)}.jpg"
        val outFile = File(attachmentsDir(), outName)
        outFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        return Prepared(file = outFile, mimeType = MIME_JPEG, fileName = outName)
    }

    private fun copyUntouched(
        uri: Uri,
        mimeType: String,
        displayName: String,
    ): Prepared? {
        val outName = "${baseName(displayName)}-${UUID.randomUUID().toString().take(8)}${extensionOf(displayName)}"
        val outFile = File(attachmentsDir(), outName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { input.copyTo(it) }
        } ?: return null
        return Prepared(file = outFile, mimeType = mimeType, fileName = outName)
    }

    private fun sampleSize(
        width: Int,
        height: Int,
    ): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= MAX_DIMENSION) sample *= 2
        return sample
    }

    private fun scaleToMaxDimension(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap
        val scale = MAX_DIMENSION.toDouble() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true,
        )
    }

    private fun baseName(displayName: String): String = displayName.substringBeforeLast('.').ifBlank { "attachment" }

    private fun extensionOf(displayName: String): String =
        displayName.substringAfterLast('.', missingDelimiterValue = "").let { if (it.isEmpty()) "" else ".$it" }

    companion object {
        /** JPEG re-encode quality — readable invoices, not thumbnail-grade (§4.2). */
        const val JPEG_QUALITY = 85

        /** Longest-side cap in pixels (§4.2). */
        const val MAX_DIMENSION = 2048

        const val MIME_JPEG = "image/jpeg"
        const val MIME_PDF = "application/pdf"
        private const val DIR_NAME = "expense_attachments"
    }
}
