package com.itsluminous.samaroh.feature.expenses.sharetarget

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A file routed to the "Create invoice" flow (ADR-078). Since ADR-086 the payload arrives
 * through the unified Save to Samaroh chooser in the app shell, which fills this holder.
 */
data class SharedInvoiceFile(
    val uri: Uri,
    val mimeType: String,
    val displayName: String,
)

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
