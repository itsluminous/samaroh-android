package com.itsluminous.samaroh.feature.expenses.sharetarget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import javax.inject.Inject
import javax.inject.Singleton

/** A file shared to the "Create invoice" share-sheet target (ADR-078). */
data class SharedInvoiceFile(
    val uri: Uri,
    val mimeType: String,
    val displayName: String,
)

/**
 * Parsing of incoming `ACTION_SEND` share-target intents (ADR-078). Pure-ish and
 * separated from the activity so the routing decision is unit-testable.
 */
object ShareTargetIntents {
    /**
     * The shared file of [intent], or null when it is not a Create-invoice share:
     * `ACTION_SEND` with an `EXTRA_STREAM` uri of an image or PDF (the manifest
     * filter already scopes delivery, this re-validates defensively). The concrete
     * mime comes from the ContentResolver when the sender only declared a wildcard.
     */
    fun parse(
        context: Context,
        intent: Intent?,
    ): SharedInvoiceFile? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return null
        val declared = intent.type
        val resolved = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val mimeType = resolved ?: declared ?: return null
        if (!mimeType.startsWith("image/") && mimeType != MIME_PDF) return null
        return SharedInvoiceFile(uri = uri, mimeType = mimeType, displayName = displayName(context, uri))
    }

    private fun displayName(
        context: Context,
        uri: Uri,
    ): String =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }
        }.getOrNull()
            ?: uri.lastPathSegment
            ?: DEFAULT_NAME

    private const val MIME_PDF = "application/pdf"
    private const val DEFAULT_NAME = "invoice"
}

/**
 * Hands the shared file from the activity's intent to the add-entry screen reached
 * after the party pick (ADR-078). A uri does not survive nav-argument encoding (and
 * the read grant is process-scoped anyway), so the file rides in this one-shot
 * process singleton: [consume] clears it so re-opening add-entry later never
 * re-attaches a stale share.
 */
@Singleton
class ShareTargetHolder
    @Inject
    constructor() {
        private var pending: SharedInvoiceFile? = null

        fun set(file: SharedInvoiceFile) {
            pending = file
        }

        fun peek(): SharedInvoiceFile? = pending

        fun consume(): SharedInvoiceFile? = pending.also { pending = null }

        fun clear() {
            pending = null
        }
    }
