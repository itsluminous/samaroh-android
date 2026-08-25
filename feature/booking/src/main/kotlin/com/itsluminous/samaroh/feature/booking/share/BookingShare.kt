package com.itsluminous.samaroh.feature.booking.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.itsluminous.samaroh.core.i18n.R
import java.io.File

/**
 * Outbound intents for the booking card (§4.1): tap-to-call, WhatsApp payment reminder,
 * invoice PDF/text sharing. Prefilled MANUAL sends only — never silent background
 * messages (§0.5 pitfalls).
 */
object BookingShare {
    private const val WHATSAPP_PACKAGE = "com.whatsapp"

    /** Opens the dialer prefilled with [phone] — no runtime permission needed. */
    fun dial(
        context: Context,
        phone: String,
    ) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Opens WhatsApp with the prefilled, localized payment-reminder [message]; falls back
     * to a generic share sheet when WhatsApp is not installed.
     */
    fun whatsAppReminder(
        context: Context,
        message: String,
    ) {
        val direct =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                setPackage(WHATSAPP_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        try {
            context.startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.booking_whatsapp_not_installed), Toast.LENGTH_SHORT).show()
            shareText(context, message)
        }
    }

    /** Generic text share (invoice-as-text and WhatsApp fallback). */
    fun shareText(
        context: Context,
        text: String,
    ) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.common_action_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Shares the generated invoice PDF via the system share sheet. */
    fun sharePdf(
        context: Context,
        absolutePath: String,
    ) {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.booking.fileprovider",
                File(absolutePath),
            )
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.common_action_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
